package com.taskflow.backend.controller;

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
    public WorkspaceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/users")
    public List<Map<String, Object>> users(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("SELECT id, name, email FROM users WHERE id = ?", Long.valueOf(jwt.getSubject()));
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("""
                SELECT p.id, p.name, p.description, COUNT(t.id) AS "taskCount", p.created_at AS "createdAt"
                FROM projects p LEFT JOIN tasks t ON t.project = p.name AND t.created_by = p.created_by
                WHERE p.created_by = ?
                GROUP BY p.id ORDER BY p.name
                """, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProject(@Valid @RequestBody ProjectRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            return jdbc.queryForMap("INSERT INTO projects(name, description, created_by) VALUES (?, ?, ?) RETURNING id, name", request.name().trim(),
                    request.description() == null ? "" : request.description().trim(), Long.valueOf(jwt.getSubject()));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with that name already exists.");
        }
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        String name = jdbc.query("SELECT name FROM projects WHERE id = ? AND created_by = ?", rs -> rs.next() ? rs.getString(1) : null, id, userId);
        if (name == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE project = ? AND created_by = ?)", name, userId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Move or delete this project's tasks first.");
        if (jdbc.update("DELETE FROM projects WHERE id = ? AND created_by = ?", id, userId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("""
                SELECT t.id, t.title, t.description, t.project, t.status, t.priority,
                       t.assignee_id AS "assigneeId", u.name AS "assigneeName", t.due_date AS "dueDate",
                       t.created_at AS "createdAt", t.updated_at AS "updatedAt"
                FROM tasks t LEFT JOIN users u ON u.id = t.assignee_id
                WHERE t.created_by = ?
                ORDER BY t.updated_at DESC, t.id DESC
                """, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> createTask(@Valid @RequestBody TaskRequest request, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        validateTask(request.status(), request.priority());
        validateAssignee(request.assigneeId(), userId);
        jdbc.update("INSERT INTO projects(name, created_by) VALUES (?, ?) ON CONFLICT (created_by, name) DO NOTHING",
                request.project().trim(), userId);
        return jdbc.queryForMap("""
                INSERT INTO tasks (title, description, project, status, priority, assignee_id, due_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, request.title().trim(), request.description() == null ? "" : request.description().trim(),
                request.project().trim(), request.status(), request.priority(), request.assigneeId(), request.dueDate(),
                userId);
    }

    @PatchMapping("/tasks/{id}")
    public Map<String, Object> updateTask(@PathVariable long id, @RequestBody TaskUpdate request, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        if (!exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE id = ? AND created_by = ?)", id, userId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (request.status() != null) validateStatus(request.status());
        if (request.priority() != null) validatePriority(request.priority());
        validateAssignee(request.assigneeId(), userId);
        return requireResult(jdbc.queryForList("""
                UPDATE tasks SET status = COALESCE(?, status), priority = COALESCE(?, priority),
                    assignee_id = CASE WHEN ? THEN ? ELSE assignee_id END,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ? AND created_by = ?
                RETURNING id, status, priority
                """, request.status(), request.priority(), Boolean.TRUE.equals(request.setAssignee()), request.assigneeId(), id, userId));
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        if (jdbc.update("DELETE FROM tasks WHERE id = ? AND created_by = ?", id, Long.valueOf(jwt.getSubject())) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/deployments")
    public List<Map<String, Object>> deployments(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("""
                SELECT d.id, d.service, d.environment, d.version, d.status, d.deployed_at AS "deployedAt",
                       u.name AS "deployedBy"
                FROM deployments d JOIN users u ON u.id = d.deployed_by
                WHERE d.deployed_by = ?
                ORDER BY d.deployed_at DESC, d.id DESC LIMIT 12
                """, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDeployment(@Valid @RequestBody DeploymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        validateDeploymentStatus(request.status());
        return jdbc.queryForMap("INSERT INTO deployments(service, environment, version, status, deployed_by) VALUES (?, ?, ?, ?, ?) RETURNING id",
                request.service().trim(), request.environment().trim(), request.version().trim(), request.status(), Long.valueOf(jwt.getSubject()));
    }

    @PatchMapping("/deployments/{id}")
    public Map<String, Object> updateDeployment(@PathVariable long id, @RequestBody DeploymentUpdate request, @AuthenticationPrincipal Jwt jwt) {
        validateDeploymentStatus(request.status());
        return requireResult(jdbc.queryForList("UPDATE deployments SET status = ? WHERE id = ? AND deployed_by = ? RETURNING id, status",
                request.status(), id, Long.valueOf(jwt.getSubject())));
    }

    private Map<String, Object> requireResult(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private boolean exists(String sql, Object... values) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, values)); }
    private void validateAssignee(Long assigneeId, long userId) {
        if (assigneeId != null && assigneeId != userId)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Private workspace tasks can only be assigned to you.");
    }
    private void validateTask(String status, String priority) { validateStatus(status); validatePriority(priority); }
    private void validateStatus(String status) {
        if (!List.of("TODO", "IN_PROGRESS", "DONE").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid task status.");
    }
    private void validatePriority(String priority) {
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid priority.");
    }
    private void validateDeploymentStatus(String status) {
        if (!List.of("DEPLOYED", "IN_PROGRESS", "FAILED").contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid deployment status.");
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
