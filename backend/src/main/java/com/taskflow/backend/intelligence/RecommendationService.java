package com.taskflow.backend.intelligence;

import com.taskflow.backend.service.WorkspaceAccess.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RecommendationService {
    private final IntelligenceStore store;
    public RecommendationService(IntelligenceStore store) { this.store = store; }

    public List<Map<String, Object>> list(Scope scope, long repositoryId) {
        store.repository(scope, repositoryId);
        List<Map<String, Object>> rows = store.jdbc.queryForList("""
                SELECT id, signal_key AS "signalKey", title, explanation, evidence, priority, complexity,
                       observed_status AS "observedStatus", analyzed_at AS "analyzedAt"
                FROM devops_recommendations WHERE repository_id = ? AND workspace_id = ?
                ORDER BY CASE observed_status WHEN 'MISSING' THEN 0 WHEN 'UNKNOWN' THEN 1 ELSE 2 END,
                         CASE priority WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END, id
                """, repositoryId, scope.id());
        for (Map<String, Object> row : rows) {
            List<Map<String, Object>> steps = store.jdbc.queryForList("""
                    SELECT id, title, instructions, file_path AS "filePath", command, validation, troubleshooting, completed,
                           updated_at AS "updatedAt" FROM implementation_steps WHERE recommendation_id = ? ORDER BY position
                    """, row.get("id"));
            row.put("steps", steps);
            row.put("status", !steps.isEmpty() && steps.stream().allMatch(step -> Boolean.TRUE.equals(step.get("completed"))) ? "COMPLETE"
                    : steps.stream().anyMatch(step -> Boolean.TRUE.equals(step.get("completed"))) ? "IN_PROGRESS" : "TODO");
        }
        return rows;
    }

    @Transactional
    public List<Map<String, Object>> sync(Scope scope, long repositoryId) {
        var repo = store.repository(scope, repositoryId);
        List<JsonNode> checks = new ArrayList<>();
        repo.analysis().path("checks").forEach(checks::add);
        for (JsonNode check : checks) {
            String key = check.path("id").asString("");
            if (!List.of("ci", "tests", "lint", "container", "deploy", "security").contains(key)) continue;
            String status = check.path("status").asString("UNKNOWN");
            if (!List.of("PRESENT", "MISSING").contains(status)) status = "UNKNOWN";
            String title = switch (key) {
                case "ci" -> "Build a reliable continuous integration pipeline";
                case "tests" -> "Make automated testing a delivery gate";
                case "lint" -> "Establish repeatable code quality checks";
                case "container" -> "Create a reproducible container build";
                case "deploy" -> "Plan a safe path to production";
                default -> "Add dependency and supply chain safeguards";
            };
            String priority = List.of("ci", "tests", "security").contains(key) ? "HIGH" : "MEDIUM";
            long id = store.jdbc.queryForObject("""
                    INSERT INTO devops_recommendations(repository_id, workspace_id, signal_key, title, explanation, evidence,
                        priority, complexity, observed_status, analyzed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (repository_id, signal_key) DO UPDATE SET explanation = EXCLUDED.explanation,
                        evidence = EXCLUDED.evidence, observed_status = EXCLUDED.observed_status, analyzed_at = EXCLUDED.analyzed_at
                    RETURNING id
                    """, Long.class, repo.id(), scope.id(), key, title,
                    "For " + repo.name() + ": " + check.path("recommendation").asString("Review the detected configuration and validate it in CI.")
                            + " Detected stack: " + repo.analysis().path("techStack") + ". A detected file does not prove successful execution.",
                    check.path("evidence").asString(check.path("detail").asString("Evidence unavailable; refresh repository analysis.")),
                    priority, key.equals("deploy") ? "ADVANCED" : "MODERATE", status, java.sql.Timestamp.from(repo.analyzedAt()));
            var steps = steps(key, repo.analysis());
            for (int position = 0; position < steps.size(); position++) {
                var step = steps.get(position);
                store.jdbc.update("""
                        INSERT INTO implementation_steps(recommendation_id, position, title, instructions, file_path, command, validation, troubleshooting)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (recommendation_id, position) DO UPDATE
                        SET instructions = EXCLUDED.instructions, file_path = EXCLUDED.file_path, command = EXCLUDED.command,
                            validation = EXCLUDED.validation, troubleshooting = EXCLUDED.troubleshooting
                        """, id, position, step.title(), step.instructions(), step.path(), step.command(), step.validation(), step.troubleshooting());
            }
        }
        store.audit(scope, "Roadmap synchronized", repo.name());
        return list(scope, repositoryId);
    }

    @Transactional
    public void complete(Scope scope, long recommendationId, long stepId, boolean completed) {
        int changed = store.jdbc.update("""
                UPDATE implementation_steps s SET completed = ?, completed_by = ?, updated_at = CURRENT_TIMESTAMP
                FROM devops_recommendations r WHERE s.recommendation_id = r.id AND r.id = ? AND s.id = ? AND r.workspace_id = ?
                """, completed, completed ? scope.userId() : null, recommendationId, stepId, scope.id());
        if (changed == 0) throw IntelligenceStore.missing();
        store.audit(scope, completed ? "Implementation step completed" : "Implementation step reopened", "Step " + stepId + " (self-reported)");
    }

    private record Step(String title, String instructions, String path, String command, String validation, String troubleshooting) { }
    private List<Step> steps(String key, JsonNode analysis) {
        String tooling = analysis.path("techStack").toString();
        String path = switch (key) {
            case "ci", "tests", "lint" -> ".github/workflows/taskflow-ci.yml";
            case "container" -> "Dockerfile.taskflow";
            case "security" -> ".github/dependabot.yml";
            default -> "deploy/compose.taskflow.yaml";
        };
        String validate = switch (key) {
            case "container" -> "docker build --file Dockerfile.taskflow --tag app:review .";
            case "deploy" -> "docker compose --env-file deploy/.env --file deploy/compose.taskflow.yaml config --quiet";
            default -> "actionlint .github/workflows/taskflow-ci.yml";
        };
        if (key.equals("security")) validate = "Review the generated Dependabot configuration, enable it in a branch, then inspect the repository Security tab.";
        return List.of(
                new Step("Review current repository evidence", "Use the report's inspected files and workflow history. Refresh older reports before making changes. Tooling evidence: " + tooling,
                        "", "git status --short", "Confirm the working tree and intended repository before editing.", "If file inspection was partial, inspect the relevant files manually. Missing evidence means not detected, rather than proof of absence."),
                new Step("Generate and review the configuration", "Open Workflow Builder, Security Center, or Deployment Center to generate the relevant draft. Check working directories, runtime versions and existing files. Use a new branch and merge with existing configuration manually.",
                        path, "git switch -c chore/devops-review", "Review the draft, required secret names and static validation results. Never paste credential values into this application.", "If a target file exists, compare it with the draft; do not overwrite it. Unknown test commands require manual configuration."),
                new Step("Validate before marking complete", "Apply only reviewed changes, run the relevant validator and inspect actual CI results. Configure required branch checks in GitHub Settings → Rules → Rulesets. Manual completion records progress and does not certify execution.",
                        path, validate, "Run the generated commands in your own trusted checkout. Confirm tests pass, inspect Actions logs, and verify runtime health before deployment.", "Check Java/Node/Python versions, lockfiles, environment variables and permissions. Keep production credentials out of pull request jobs.")
        );
    }
}
