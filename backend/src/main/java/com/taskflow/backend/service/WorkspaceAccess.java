package com.taskflow.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class WorkspaceAccess {
    private final JdbcTemplate jdbc;

    public WorkspaceAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Scope(long id, long userId, boolean team) { }

    @Transactional
    public Scope resolve(Jwt jwt, Long teamId) {
        long userId = Long.parseLong(jwt.getSubject());
        if (teamId != null) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT w.id FROM workspaces w JOIN workspace_members m ON m.workspace_id = w.id
                    WHERE w.id = ? AND w.kind = 'TEAM' AND m.user_id = ?
                    """, Long.class, teamId, userId);
            if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team workspace was not found.");
            return new Scope(ids.getFirst(), userId, true);
        }
        List<Long> ids = jdbc.queryForList("SELECT id FROM workspaces WHERE owner_id = ? AND kind = 'PERSONAL'", Long.class, userId);
        if (!ids.isEmpty()) return new Scope(ids.getFirst(), userId, false);
        long id = jdbc.queryForObject("""
                INSERT INTO workspaces(name, kind, owner_id) VALUES ('Private workspace', 'PERSONAL', ?)
                ON CONFLICT (owner_id) WHERE kind = 'PERSONAL' DO UPDATE SET owner_id = EXCLUDED.owner_id
                RETURNING id
                """, Long.class, userId);
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", id, userId);
        return new Scope(id, userId, false);
    }

    public void validateAssignee(Scope scope, Long assigneeId) {
        if (assigneeId == null) return;
        boolean member = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM workspace_members WHERE workspace_id = ? AND user_id = ?)",
                Boolean.class, scope.id(), assigneeId));
        if (!member) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                scope.team() ? "Assignee must be a member of this team." : "Private workspace tasks can only be assigned to you.");
    }
}
