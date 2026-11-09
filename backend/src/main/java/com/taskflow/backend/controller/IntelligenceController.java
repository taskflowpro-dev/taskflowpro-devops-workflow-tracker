package com.taskflow.backend.controller;

import com.taskflow.backend.intelligence.*;
import com.taskflow.backend.service.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {
    private final WorkspaceAccess access;
    private final IntelligenceStore store;
    private final RecommendationService recommendations;
    private final ConfigurationService configurations;
    private final DeploymentAdvisoryService deployments;
    private final ConversationService conversations;
    public IntelligenceController(WorkspaceAccess access, IntelligenceStore store, RecommendationService recommendations,
            ConfigurationService configurations, DeploymentAdvisoryService deployments, ConversationService conversations) {
        this.access = access; this.store = store; this.recommendations = recommendations; this.configurations = configurations;
        this.deployments = deployments; this.conversations = conversations;
    }
    @GetMapping("/repositories/{repoId}/roadmap")
    public List<Map<String, Object>> roadmap(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId) {
        return recommendations.list(access.resolve(jwt, teamId), repoId);
    }
    @PostMapping("/repositories/{repoId}/roadmap")
    public List<Map<String, Object>> sync(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId) {
        return recommendations.sync(access.resolve(jwt, teamId), repoId);
    }
    @PatchMapping("/recommendations/{id}/steps/{stepId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void step(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long id,
                     @PathVariable long stepId, @Valid @RequestBody Progress request) {
        recommendations.complete(access.resolve(jwt, teamId), id, stepId, request.completed());
    }
    @GetMapping("/repositories/{repoId}/configurations")
    public List<Map<String, Object>> configurations(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId) {
        return configurations.list(access.resolve(jwt, teamId), repoId);
    }
    @PostMapping("/repositories/{repoId}/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> generate(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @Valid @RequestBody Generate request) {
        return configurations.generate(access.resolve(jwt, teamId), repoId, request.kind(), request.tests(), request.lint(), request.security(), request.container(), request.publish(), request.profileIndex());
    }
    @PostMapping("/repositories/{repoId}/configurations/{id}/validate")
    public Map<String, Object> validate(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @PathVariable long id) {
        return configurations.validate(access.resolve(jwt, teamId), repoId, id);
    }
    @GetMapping("/repositories/{repoId}/environments")
    public List<Map<String, Object>> environments(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId) {
        var scope = access.resolve(jwt, teamId); store.repository(scope, repoId);
        var rows = store.jdbc.queryForList("SELECT id, name, target, status, plan::text, created_at AS \"createdAt\" FROM deployment_environments WHERE repository_id = ? AND workspace_id = ? ORDER BY id DESC LIMIT 50", repoId, scope.id());
        rows.forEach(row -> row.put("plan", store.parse(row.get("plan")))); return rows;
    }
    @PostMapping("/repositories/{repoId}/environments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> plan(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @Valid @RequestBody Environment request) {
        var scope = access.resolve(jwt, teamId); var repo = store.repository(scope, repoId);
        var plan = deployments.plan(repo, request.target());
        var row = store.jdbc.queryForMap("INSERT INTO deployment_environments(repository_id, workspace_id, name, target, plan, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?) RETURNING id, name, target, status", repoId, scope.id(), request.name().trim(), request.target(), store.json(plan), scope.userId());
        row.put("plan", plan); store.audit(scope, "Deployment plan created", repo.name() + " · " + request.name()); return row;
    }
    @PatchMapping("/repositories/{repoId}/environments/{id}")
    public Map<String, Object> review(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @PathVariable long id, @Valid @RequestBody Review request) {
        var scope = access.resolve(jwt, teamId); store.repository(scope, repoId);
        var rows = store.jdbc.queryForList("UPDATE deployment_environments SET status = ? WHERE id = ? AND repository_id = ? AND workspace_id = ? RETURNING id, status", request.status(), id, repoId, scope.id());
        if (rows.isEmpty()) throw IntelligenceStore.missing(); return rows.getFirst();
    }
    @GetMapping("/repositories/{repoId}/conversations")
    public List<Map<String, Object>> conversations(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId) {
        return conversations.list(access.resolve(jwt, teamId), repoId);
    }
    @PostMapping("/repositories/{repoId}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> conversation(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @Valid @RequestBody Conversation request) {
        return conversations.create(access.resolve(jwt, teamId), repoId, request.title().trim());
    }
    @GetMapping("/repositories/{repoId}/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @PathVariable long id) {
        return conversations.messages(access.resolve(jwt, teamId), repoId, id);
    }
    @PostMapping("/repositories/{repoId}/conversations/{id}/messages")
    public List<Map<String, Object>> message(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId, @PathVariable long repoId, @PathVariable long id, @Valid @RequestBody Message request) {
        return conversations.send(access.resolve(jwt, teamId), repoId, id, request.content().trim());
    }
    @GetMapping("/activity")
    public List<Map<String, Object>> activity(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) Long teamId) { return store.activity(access.resolve(jwt, teamId)); }
    @GetMapping("/integrations")
    public Map<String, Object> integrations(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("ai", conversations.status(), "github", Map.of("mode", "server-configured-read-only", "writesEnabled", false), "deployments", Map.of("executionEnabled", false));
    }
    @GetMapping("/preferences")
    public Map<String, Object> preferences(@AuthenticationPrincipal Jwt jwt) {
        var rows = store.jdbc.queryForList("SELECT compact_mode AS \"compactMode\", reduce_motion AS \"reduceMotion\" FROM user_preferences WHERE user_id = ?", Long.parseLong(jwt.getSubject()));
        return rows.isEmpty() ? Map.of("compactMode", false, "reduceMotion", false) : rows.getFirst();
    }
    @PatchMapping("/account")
    public Map<String, Object> account(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Account request) {
        store.jdbc.update("UPDATE users SET name = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", request.name().trim(), Long.parseLong(jwt.getSubject()));
        return Map.of("name", request.name().trim());
    }
    @PutMapping("/preferences")
    public Map<String, Object> preferences(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Preferences request) {
        store.jdbc.update("INSERT INTO user_preferences(user_id, compact_mode, reduce_motion) VALUES (?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET compact_mode = EXCLUDED.compact_mode, reduce_motion = EXCLUDED.reduce_motion, updated_at = CURRENT_TIMESTAMP", Long.parseLong(jwt.getSubject()), request.compactMode(), request.reduceMotion());
        return preferences(jwt);
    }
    public record Progress(@NotNull Boolean completed) { }
    public record Account(@NotBlank @Size(max=100) String name) { }
    public record Generate(@NotNull @Pattern(regexp="workflow|docker|deployment|security|release") String kind, Boolean tests, Boolean lint, Boolean security, Boolean container, Boolean publish, @Min(0) @Max(19) Integer profileIndex) {
        public Generate { tests = Boolean.TRUE.equals(tests); lint = Boolean.TRUE.equals(lint); security = Boolean.TRUE.equals(security); container = Boolean.TRUE.equals(container); publish = Boolean.TRUE.equals(publish); }
    }
    public record Environment(@NotBlank @Size(max=80) String name, @NotNull @Pattern(regexp="ubuntu-vps|managed-cloud") String target) { }
    public record Review(@NotNull @Pattern(regexp="PLANNED|REVIEWED") String status) { }
    public record Conversation(@NotBlank @Size(max=160) String title) { }
    public record Message(@NotBlank @Size(max=4000) String content) { }
    public record Preferences(@NotNull Boolean compactMode, @NotNull Boolean reduceMotion) { }
}
