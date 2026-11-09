package com.taskflow.backend.intelligence;

import com.taskflow.backend.service.WorkspaceAccess.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
public class ConfigurationService {
    private final IntelligenceStore store;
    private final WorkflowGenerationService generator;
    private final ConfigurationValidator validator;
    private final DeploymentAdvisoryService deployment;
    public ConfigurationService(IntelligenceStore store, WorkflowGenerationService generator, ConfigurationValidator validator, DeploymentAdvisoryService deployment) {
        this.store = store; this.generator = generator; this.validator = validator; this.deployment = deployment;
    }
    public List<Map<String, Object>> list(Scope scope, long repoId) {
        store.repository(scope, repoId);
        var rows = store.jdbc.queryForList("""
                SELECT id, kind, file_path AS "filePath", content, metadata::text, created_at AS "createdAt"
                FROM generated_configurations WHERE repository_id = ? AND workspace_id = ? ORDER BY id DESC LIMIT 50
                """, repoId, scope.id());
        rows.forEach(row -> row.put("metadata", store.parse(row.get("metadata"))));
        return rows;
    }
    @Transactional
    public Map<String, Object> generate(Scope scope, long repoId, String kind, boolean tests, boolean lint, boolean security, boolean container, boolean publish) {
        return generate(scope, repoId, kind, tests, lint, security, container, publish, null);
    }
    @Transactional
    public Map<String, Object> generate(Scope scope, long repoId, String kind, boolean tests, boolean lint, boolean security, boolean container, boolean publish, Integer profileIndex) {
        var repo = store.repository(scope, repoId);
        var draft = kind.equals("deployment") ? deployment.compose() : generator.generate(repo.analysis(), kind, tests, lint, security, container, publish, profileIndex);
        String storedKind = kind.equals("release") ? "workflow" : kind;
        var metadata = new java.util.LinkedHashMap<>(draft.metadata());
        metadata.put("validation", validator.validate(storedKind, draft.content()));
        metadata.put("analysisTimestamp", repo.analyzedAt().toString());
        var row = store.jdbc.queryForMap("""
                INSERT INTO generated_configurations(repository_id, workspace_id, kind, file_path, content, metadata, created_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id, kind, file_path AS "filePath", content, created_at AS "createdAt"
                """, repoId, scope.id(), storedKind, draft.path(), draft.content(), store.json(metadata), scope.userId());
        row.put("metadata", metadata);
        store.audit(scope, "Configuration draft generated", repo.name() + " · " + draft.path());
        return row;
    }
    public Map<String, Object> validate(Scope scope, long repoId, long id) {
        store.repository(scope, repoId);
        var rows = store.jdbc.queryForList("SELECT kind, content FROM generated_configurations WHERE id = ? AND repository_id = ? AND workspace_id = ?", id, repoId, scope.id());
        if (rows.isEmpty()) throw IntelligenceStore.missing();
        return validator.validate(rows.getFirst().get("kind").toString(), rows.getFirst().get("content").toString());
    }
}
