package com.taskflow.backend.intelligence;

import com.taskflow.backend.service.WorkspaceAccess.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class IntelligenceStore {
    public final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public IntelligenceStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    public record Repository(long id, String name, Instant analyzedAt, JsonNode analysis) { }
    public Repository repository(Scope scope, long id) {
        return jdbc.query("SELECT id, full_name, analyzed_at, last_analysis::text FROM tracked_repositories WHERE id = ? AND workspace_id = ?",
                rs -> {
                    if (!rs.next()) throw missing();
                    return new Repository(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant(), mapper.readTree(rs.getString(4)));
                }, id, scope.id());
    }
    public String json(Object value) { return mapper.writeValueAsString(value); }
    public JsonNode parse(Object value) { return mapper.readTree(value.toString()); }
    public void audit(Scope scope, String action, String detail) {
        jdbc.update("INSERT INTO workspace_activity(workspace_id, actor_id, action, detail) VALUES (?, ?, ?, ?)",
                scope.id(), scope.userId(), action, detail.substring(0, Math.min(500, detail.length())));
    }
    public List<Map<String, Object>> activity(Scope scope) {
        return jdbc.queryForList("""
                SELECT a.id, a.action, a.detail, a.created_at AS "createdAt", u.name AS actor
                FROM workspace_activity a JOIN users u ON u.id = a.actor_id
                WHERE a.workspace_id = ? ORDER BY a.created_at DESC, a.id DESC LIMIT 100
                """, scope.id());
    }
    public static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record was not found in this workspace."); }
}
