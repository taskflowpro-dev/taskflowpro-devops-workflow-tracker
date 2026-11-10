package com.taskflow.backend.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.taskflow.backend.service.GitHubRepositoryAnalyzer;
import com.taskflow.backend.service.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final GitHubRepositoryAnalyzer analyzer;
    private final WorkspaceAccess access;

    public RepositoryController(JdbcTemplate jdbc, ObjectMapper mapper, GitHubRepositoryAnalyzer analyzer, WorkspaceAccess access) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.analyzer = analyzer;
        this.access = access;
    }

    @GetMapping
    public List<Map<String, Object>> repositories(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.query("""
                SELECT r.id, r.url, r.full_name AS "fullName", r.description, r.language, r.analyzed_at AS "analyzedAt",
                       r.project_id AS "projectId", p.name AS "projectName", r.last_analysis::text AS "analysisJson"
                FROM tracked_repositories r LEFT JOIN projects p ON p.id = r.project_id
                WHERE r.workspace_id = ? ORDER BY r.analyzed_at DESC
                """, (row, index) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.getLong("id"));
            result.put("url", row.getString("url"));
            result.put("fullName", row.getString("fullName"));
            result.put("description", row.getString("description"));
            result.put("language", row.getString("language") == null ? "Not detected" : row.getString("language"));
            result.put("analyzedAt", row.getTimestamp("analyzedAt").toInstant().toString());
            result.put("projectId", row.getObject("projectId") == null ? null : row.getLong("projectId"));
            result.put("projectName", row.getString("projectName"));
            result.put("analysisJson", row.getString("analysisJson"));
            return withParsedAnalysis(result);
        }, scope.id());
    }

    @PostMapping("/analyze")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> analyze(@Valid @RequestBody AnalyzeRequest request, @AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        GitHubRepositoryAnalyzer.RepositoryRef ref = analyzer.parseUrl(request.url());
        return analyzeAndSave(ref, scope, null);
    }

    @PostMapping("/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        String url = jdbc.query("SELECT url FROM tracked_repositories WHERE id = ? AND workspace_id = ?",
                row -> row.next() ? row.getString(1) : null, id, scope.id());
        if (url == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
        return analyzeAndSave(analyzer.parseUrl(url), scope, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        if (jdbc.update("DELETE FROM tracked_repositories WHERE id = ? AND workspace_id = ?", id, scope.id()) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
    }

    @PutMapping("/{id}/project")
    public Map<String, Object> linkProject(@PathVariable long id, @Valid @RequestBody ProjectLinkRequest request,
                                           @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM projects WHERE id = ? AND workspace_id = ?)",
                Boolean.class, request.projectId(), scope.id())))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project was not found in this workspace.");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE tracked_repositories SET project_id = ? WHERE id = ? AND workspace_id = ?
                RETURNING id, project_id AS "projectId"
                """, request.projectId(), id, scope.id());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
        Map<String, Object> result = rows.getFirst();
        result.put("projectName", jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, request.projectId()));
        return result;
    }

    private Map<String, Object> analyzeAndSave(GitHubRepositoryAnalyzer.RepositoryRef ref, WorkspaceAccess.Scope scope, Long repositoryId) {
        analyzer.assertAccess(ref, scope.id());
        Map<String, Object> analysis = analyzer.analyze(ref.canonicalUrl());
        String fullName = (String) analysis.get("fullName");
        String description = (String) analysis.get("description");
        String language = "Not detected".equals(analysis.get("language")) ? null : (String) analysis.get("language");
        Long linkedProjectId = repositoryId == null
                ? jdbc.query("SELECT project_id FROM tracked_repositories WHERE workspace_id = ? AND owner = ? AND repository = ?",
                    rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, scope.id(), ref.owner(), ref.name())
                : jdbc.query("SELECT project_id FROM tracked_repositories WHERE id = ? AND workspace_id = ?",
                    rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null, repositoryId, scope.id());
        if (linkedProjectId == null) {
            String projectName = fullName.substring(0, Math.min(100, fullName.length()));
            jdbc.update("""
                    INSERT INTO projects(name, description, created_by, workspace_id) VALUES (?, ?, ?, ?)
                    ON CONFLICT (workspace_id, name) DO NOTHING
                    """, projectName, description == null ? "" : description, scope.userId(), scope.id());
            linkedProjectId = jdbc.queryForObject("SELECT id FROM projects WHERE workspace_id = ? AND name = ?",
                    Long.class, scope.id(), projectName);
        }
        String analysisJson;
        try { analysisJson = mapper.writeValueAsString(analysis); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not store repository analysis.", exception); }

        Map<String, Object> saved;
        if (repositoryId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    UPDATE tracked_repositories SET url = ?, full_name = ?, description = ?, language = ?,
                        last_analysis = ?::jsonb, analyzed_at = CURRENT_TIMESTAMP, project_id = COALESCE(project_id, ?)
                    WHERE id = ? AND workspace_id = ?
                    RETURNING id, url, full_name AS "fullName", description, language, analyzed_at AS "analyzedAt", project_id AS "projectId"
                    """, ref.canonicalUrl(), fullName, description, language, analysisJson, linkedProjectId, repositoryId, scope.id());
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
            saved = rows.getFirst();
        } else {
            saved = jdbc.queryForMap("""
                    INSERT INTO tracked_repositories(owner, repository, url, full_name, description, language, last_analysis, added_by, workspace_id, project_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (workspace_id, owner, repository) DO UPDATE SET url = EXCLUDED.url, full_name = EXCLUDED.full_name,
                        description = EXCLUDED.description, language = EXCLUDED.language,
                        last_analysis = EXCLUDED.last_analysis, analyzed_at = CURRENT_TIMESTAMP,
                        project_id = COALESCE(tracked_repositories.project_id, EXCLUDED.project_id)
                    RETURNING id, url, full_name AS "fullName", description, language, analyzed_at AS "analyzedAt", project_id AS "projectId"
                    """, ref.owner(), ref.name(), ref.canonicalUrl(), fullName, description, language, analysisJson, scope.userId(), scope.id(), linkedProjectId);
        }

        Long projectId = ((Number) saved.get("projectId")).longValue();
        String savedProjectName = jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, projectId);
        saved.put("projectName", savedProjectName);
        saved.put("analysis", analysis);
        return saved;
    }

    private Map<String, Object> withParsedAnalysis(Map<String, Object> result) {
        Object json = result.remove("analysisJson");
        try { result.put("analysis", mapper.readValue((String) json, Map.class)); }
        catch (JacksonException exception) { throw new IllegalStateException("Saved repository analysis could not be read.", exception); }
        return result;
    }

    public record AnalyzeRequest(@NotBlank @Size(max = 300) String url) { }
    public record ProjectLinkRequest(@NotNull Long projectId) { }
}
