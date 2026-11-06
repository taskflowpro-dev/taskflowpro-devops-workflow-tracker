package com.taskflow.backend.controller;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.taskflow.backend.service.GitHubRepositoryAnalyzer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    public RepositoryController(JdbcTemplate jdbc, ObjectMapper mapper, GitHubRepositoryAnalyzer analyzer) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.analyzer = analyzer;
    }

    @GetMapping
    public List<Map<String, Object>> repositories(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.query("""
                SELECT id, url, full_name AS "fullName", description, language, analyzed_at AS "analyzedAt",
                       last_analysis::text AS "analysisJson"
                FROM tracked_repositories WHERE added_by = ? ORDER BY analyzed_at DESC
                """, (row, index) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", row.getLong("id"));
            result.put("url", row.getString("url"));
            result.put("fullName", row.getString("fullName"));
            result.put("description", row.getString("description"));
            result.put("language", row.getString("language") == null ? "Not detected" : row.getString("language"));
            result.put("analyzedAt", row.getTimestamp("analyzedAt").toInstant().toString());
            result.put("analysisJson", row.getString("analysisJson"));
            return withParsedAnalysis(result);
        }, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/analyze")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> analyze(@Valid @RequestBody AnalyzeRequest request, @AuthenticationPrincipal Jwt jwt) {
        GitHubRepositoryAnalyzer.RepositoryRef ref = analyzer.parseUrl(request.url());
        return analyzeAndSave(ref, Long.parseLong(jwt.getSubject()), null);
    }

    @PostMapping("/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        String url = jdbc.query("SELECT url FROM tracked_repositories WHERE id = ? AND added_by = ?",
                row -> row.next() ? row.getString(1) : null, id, userId);
        if (url == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
        return analyzeAndSave(analyzer.parseUrl(url), userId, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        if (jdbc.update("DELETE FROM tracked_repositories WHERE id = ? AND added_by = ?", id, Long.valueOf(jwt.getSubject())) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
    }

    private Map<String, Object> analyzeAndSave(GitHubRepositoryAnalyzer.RepositoryRef ref, long userId, Long repositoryId) {
        Map<String, Object> analysis = analyzer.analyze(ref.canonicalUrl());
        String fullName = (String) analysis.get("fullName");
        String description = (String) analysis.get("description");
        String language = "Not detected".equals(analysis.get("language")) ? null : (String) analysis.get("language");
        String analysisJson;
        try { analysisJson = mapper.writeValueAsString(analysis); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not store repository analysis.", exception); }

        Map<String, Object> saved;
        if (repositoryId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    UPDATE tracked_repositories SET url = ?, full_name = ?, description = ?, language = ?,
                        last_analysis = ?::jsonb, analyzed_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND added_by = ?
                    RETURNING id, url, full_name AS "fullName", description, language, analyzed_at AS "analyzedAt"
                    """, ref.canonicalUrl(), fullName, description, language, analysisJson, repositoryId, userId);
            if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository was not found.");
            saved = rows.getFirst();
        } else {
            saved = jdbc.queryForMap("""
                    INSERT INTO tracked_repositories(owner, repository, url, full_name, description, language, last_analysis, added_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (added_by, owner, repository) DO UPDATE SET url = EXCLUDED.url, full_name = EXCLUDED.full_name,
                        description = EXCLUDED.description, language = EXCLUDED.language,
                        last_analysis = EXCLUDED.last_analysis, analyzed_at = CURRENT_TIMESTAMP
                    RETURNING id, url, full_name AS "fullName", description, language, analyzed_at AS "analyzedAt"
                    """, ref.owner(), ref.name(), ref.canonicalUrl(), fullName, description, language, analysisJson, userId);
        }

        saved.put("analysis", analysis);
        return saved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withParsedAnalysis(Map<String, Object> result) {
        Object json = result.remove("analysisJson");
        try { result.put("analysis", mapper.readValue((String) json, Map.class)); }
        catch (JacksonException exception) { throw new IllegalStateException("Saved repository analysis could not be read.", exception); }
        return result;
    }

    public record AnalyzeRequest(@NotBlank @Size(max = 300) String url) { }
}
