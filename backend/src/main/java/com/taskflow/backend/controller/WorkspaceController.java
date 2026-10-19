package com.taskflow.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    public List<Map<String, Object>> users() {
        return jdbc.queryForList("SELECT id, name, email FROM users ORDER BY name");
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return jdbc.queryForList("""
                SELECT p.id, p.name, p.description, COUNT(t.id) AS "taskCount", p.created_at AS "createdAt"
                FROM projects p LEFT JOIN tasks t ON t.project = p.name
                GROUP BY p.id ORDER BY p.name
                """);
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createProject(@Valid @RequestBody ProjectRequest request, @AuthenticationPrincipal Jwt jwt) {
        try {
            jdbc.update("INSERT INTO projects(name, description, created_by) VALUES (?, ?, ?)", request.name().trim(),
                    request.description() == null ? "" : request.description().trim(), Long.valueOf(jwt.getSubject()));
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A project with that name already exists.");
        }
        return jdbc.queryForMap("SELECT id, name FROM projects WHERE name = ?", request.name().trim());
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProject(@PathVariable long id) {
        String name = jdbc.query("SELECT name FROM projects WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
        if (name == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE project = ?)", name))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Move or delete this project's tasks first.");
        jdbc.update("DELETE FROM projects WHERE id = ?", id);
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return jdbc.queryForList("""
                SELECT t.id, t.title, t.description, t.project, t.status, t.priority,
                       t.assignee_id AS "assigneeId", u.name AS "assigneeName", t.due_date AS "dueDate",
                       t.created_at AS "createdAt", t.updated_at AS "updatedAt"
                FROM tasks t LEFT JOIN users u ON u.id = t.assignee_id
                ORDER BY t.updated_at DESC, t.id DESC
                """);
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTask(@Valid @RequestBody TaskRequest request, @AuthenticationPrincipal Jwt jwt) {
        validateTask(request.status(), request.priority());
        jdbc.update("INSERT INTO projects(name, created_by) VALUES (?, ?) ON CONFLICT (name) DO NOTHING",
                request.project().trim(), Long.valueOf(jwt.getSubject()));
        if (request.assigneeId() != null && !exists("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)", request.assigneeId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee was not found.");
        jdbc.update("""
                INSERT INTO tasks (title, description, project, status, priority, assignee_id, due_date, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, request.title().trim(), request.description() == null ? "" : request.description().trim(),
                request.project().trim(), request.status(), request.priority(), request.assigneeId(), request.dueDate(),
                Long.valueOf(jwt.getSubject()));
        return jdbc.queryForMap("SELECT id FROM tasks ORDER BY id DESC LIMIT 1");
    }

    @PatchMapping("/tasks/{id}")
    public Map<String, Object> updateTask(@PathVariable long id, @RequestBody TaskUpdate request) {
        if (!exists("SELECT EXISTS(SELECT 1 FROM tasks WHERE id = ?)", id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (request.status() != null) validateStatus(request.status());
        if (request.priority() != null) validatePriority(request.priority());
        if (request.assigneeId() != null && !exists("SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)", request.assigneeId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Assignee was not found.");
        jdbc.update("""
                UPDATE tasks SET status = COALESCE(?, status), priority = COALESCE(?, priority),
                    assignee_id = CASE WHEN ? THEN ? ELSE assignee_id END,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, request.status(), request.priority(), request.setAssignee(), request.assigneeId(), id);
        return jdbc.queryForMap("SELECT id, status, priority FROM tasks WHERE id = ?", id);
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(@PathVariable long id) {
        if (jdbc.update("DELETE FROM tasks WHERE id = ?", id) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    @GetMapping("/deployments")
    public List<Map<String, Object>> deployments() {
        return jdbc.queryForList("""
                SELECT d.id, d.service, d.environment, d.version, d.status, d.deployed_at AS "deployedAt",
                       u.name AS "deployedBy"
                FROM deployments d JOIN users u ON u.id = d.deployed_by
                ORDER BY d.deployed_at DESC, d.id DESC LIMIT 12
                """);
    }

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createDeployment(@Valid @RequestBody DeploymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        validateDeploymentStatus(request.status());
        jdbc.update("INSERT INTO deployments(service, environment, version, status, deployed_by) VALUES (?, ?, ?, ?, ?)",
                request.service().trim(), request.environment().trim(), request.version().trim(), request.status(), Long.valueOf(jwt.getSubject()));
        return jdbc.queryForMap("SELECT id FROM deployments ORDER BY id DESC LIMIT 1");
    }

    @PatchMapping("/deployments/{id}")
    public Map<String, Object> updateDeployment(@PathVariable long id, @RequestBody DeploymentUpdate request) {
        validateDeploymentStatus(request.status());
        if (jdbc.update("UPDATE deployments SET status = ? WHERE id = ?", request.status(), id) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return jdbc.queryForMap("SELECT id, status FROM deployments WHERE id = ?", id);
    }

    private boolean exists(String sql, Object value) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, value)); }
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
    public record TaskUpdate(String status, String priority, Long assigneeId, boolean setAssignee) { }
    public record ProjectRequest(@NotBlank @Size(max = 100) String name, String description) { }
    public record DeploymentRequest(@NotBlank @Size(max = 100) String service,
                                    @NotBlank @Size(max = 40) String environment,
                                    @NotBlank @Size(max = 80) String version, String status) {
        public DeploymentRequest { if (status == null) status = "DEPLOYED"; }
    }
    public record DeploymentUpdate(String status) { }
}
