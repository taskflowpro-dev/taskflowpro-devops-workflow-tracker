package com.taskflow.backend.controller;

import com.taskflow.backend.service.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkspaceController {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;
    public WorkspaceController(JdbcTemplate jdbc, WorkspaceAccess access) { this.jdbc = jdbc; this.access = access; }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.queryForList("""
                SELECT u.id, u.name, u.email FROM users u JOIN workspace_members m ON m.user_id = u.id
                WHERE m.workspace_id = ? ORDER BY u.name
                """, scope.id());
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.queryForList("""
                SELECT p.id, p.name, p.description, COUNT(DISTINCT t.id) AS "taskCount", p.created_at AS "createdAt",
                       COUNT(DISTINCT r.id) AS "repositoryCount", MAX(r.full_name) AS "repositoryName", MAX(r.url) AS "repositoryUrl"
                FROM projects p LEFT JOIN tasks t ON t.project = p.name AND t.workspace_id = p.workspace_id
                LEFT JOIN tracked_repositories r ON r.project_id = p.id AND r.workspace_id = p.workspace_id
                WHERE p.workspace_id = ?
                GROUP BY p.id ORDER BY p.name
                """, scope.id());
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProject(@Valid @RequestBody ProjectRequest request, @AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        try {
            return jdbc.queryForMap("INSERT INTO projects(name, description, created_by, workspace_id) VALUES (?, ?, ?, ?) RETURNING id, name", request.name().trim(),
                    request.description() == null ? "" : request.description().trim(), scope.userId(), scope.id());
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with that name already exists.");
        }
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        String name = jdbc.query("SELECT name FROM projects WHERE id = ? AND workspace_id = ?", rs -> rs.next() ? rs.getString(1) : null, id, scope.id());
        if (name == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE project = ? AND workspace_id = ?)", name, scope.id()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Move or delete this project's tasks first.");
        if (jdbc.update("DELETE FROM projects WHERE id = ? AND workspace_id = ?", id, scope.id()) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.queryForList("""
                SELECT t.id, t.title, t.description, t.project, t.status, t.priority,
                       t.assignee_id AS "assigneeId", u.name AS "assigneeName", t.due_date AS "dueDate",
                       t.created_at AS "createdAt", t.updated_at AS "updatedAt"
                FROM tasks t LEFT JOIN users u ON u.id = t.assignee_id
                WHERE t.workspace_id = ?
                ORDER BY t.updated_at DESC, t.id DESC
                """, scope.id());
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createTask(@Valid @RequestBody TaskRequest request, @AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        validateTask(request.status(), request.priority());
        access.validateAssignee(scope, request.assigneeId());
        jdbc.update("INSERT INTO projects(name, created_by, workspace_id) VALUES (?, ?, ?) ON CONFLICT (workspace_id, name) DO NOTHING",
                request.project().trim(), scope.userId(), scope.id());
        return jdbc.queryForMap("""
                INSERT INTO tasks (title, description, project, status, priority, assignee_id, due_date, created_by, workspace_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, request.title().trim(), request.description() == null ? "" : request.description().trim(),
                request.project().trim(), request.status(), request.priority(), request.assigneeId(), request.dueDate(),
                scope.userId(), scope.id());
    }

    @PatchMapping("/tasks/{id}")
    public Map<String, Object> updateTask(@PathVariable long id, @RequestBody TaskUpdate request, @AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        if (!exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE id = ? AND workspace_id = ?)", id, scope.id()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (request.status() != null) validateStatus(request.status());
        if (request.priority() != null) validatePriority(request.priority());
        access.validateAssignee(scope, request.assigneeId());
        return requireResult(jdbc.queryForList("""
                UPDATE tasks SET status = COALESCE(?, status), priority = COALESCE(?, priority),
                    assignee_id = CASE WHEN ? THEN ? ELSE assignee_id END,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND workspace_id = ?
                RETURNING id, status, priority
                """, request.status(), request.priority(), Boolean.TRUE.equals(request.setAssignee()), request.assigneeId(), id, scope.id()));
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable long id, @AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        if (jdbc.update("DELETE FROM tasks WHERE id = ? AND workspace_id = ?", id, scope.id()) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/deployments")
    public List<Map<String, Object>> deployments(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.queryForList("""
                SELECT d.id, d.service, d.environment, d.version, d.status, d.deployed_at AS "deployedAt",
                       u.name AS "deployedBy"
                FROM deployments d JOIN users u ON u.id = d.deployed_by
                WHERE d.workspace_id = ?
                ORDER BY d.deployed_at DESC, d.id DESC LIMIT 12
                """, scope.id());
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDeployment(@Valid @RequestBody DeploymentRequest request, @AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        validateDeploymentStatus(request.status());
        return jdbc.queryForMap("INSERT INTO deployments(service, environment, version, status, deployed_by, workspace_id) VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
                request.service().trim(), request.environment().trim(), request.version().trim(), request.status(), scope.userId(), scope.id());
    }

    @PatchMapping("/deployments/{id}")
    public Map<String, Object> updateDeployment(@PathVariable long id, @RequestBody DeploymentUpdate request, @AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) Long teamId) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        validateDeploymentStatus(request.status());
        return requireResult(jdbc.queryForList("UPDATE deployments SET status = ? WHERE id = ? AND workspace_id = ? RETURNING id, status",
                request.status(), id, scope.id()));
    }

    private Map<String, Object> requireResult(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private boolean exists(String sql, Object... values) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, values)); }
    private void validateTask(String status, String priority) { validateStatus(status); validatePriority(priority); }
    private void validateStatus(String status) {
        if (status == null || !List.of("TODO", "IN_PROGRESS", "DONE").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task status.");
    }
    private void validatePriority(String priority) {
        if (priority == null || !List.of("LOW", "MEDIUM", "HIGH").contains(priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid priority.");
    }
    private void validateDeploymentStatus(String status) {
        if (status == null || !List.of("DEPLOYED", "IN_PROGRESS", "FAILED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid deployment status.");
    }

    public record TaskRequest(@NotBlank @Size(max = 160) String title, String description,
                              @NotBlank @Size(max = 100) String project, String status, String priority,
                              Long assigneeId, java.time.LocalDate dueDate) {
        public TaskRequest { if (status == null) status = "TODO"; if (priority == null) priority = "MEDIUM"; }
    }
    public record TaskUpdate(String status, String priority, Long assigneeId, Boolean setAssignee) { }
    public record ProjectRequest(@NotBlank @Size(max = 100) String name, String description) { }
    public record DeploymentRequest(@NotBlank @Size(max = 100) String service,
                                    @NotBlank @Size(max = 40) String environment,
                                    @NotBlank @Size(max = 80) String version, String status) {
        public DeploymentRequest { if (status == null) status = "DEPLOYED"; }
    }
    public record DeploymentUpdate(String status) { }
}
