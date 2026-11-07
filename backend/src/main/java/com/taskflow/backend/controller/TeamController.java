package com.taskflow.backend.controller;

import com.taskflow.backend.service.WorkspaceAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/teams")
public class TeamController {
    private final JdbcTemplate jdbc;
    private final WorkspaceAccess access;

    public TeamController(JdbcTemplate jdbc, WorkspaceAccess access) { this.jdbc = jdbc; this.access = access; }

    @GetMapping
    public List<Map<String, Object>> teams(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("""
                SELECT w.id, w.name, w.owner_id AS "ownerId", COUNT(all_members.user_id) AS "memberCount"
                FROM workspaces w JOIN workspace_members mine ON mine.workspace_id = w.id AND mine.user_id = ?
                JOIN workspace_members all_members ON all_members.workspace_id = w.id
                WHERE w.kind = 'TEAM' GROUP BY w.id ORDER BY w.name, w.id
                """, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody TeamRequest request, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        long id = jdbc.queryForObject("INSERT INTO workspaces(name, kind, owner_id) VALUES (?, 'TEAM', ?) RETURNING id",
                Long.class, request.name().trim(), userId);
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id) VALUES (?, ?)", id, userId);
        return team(id, jwt);
    }

    @GetMapping("/{teamId}")
    public Map<String, Object> team(@PathVariable long teamId, @AuthenticationPrincipal Jwt jwt) {
        WorkspaceAccess.Scope scope = access.resolve(jwt, teamId);
        return jdbc.queryForMap("""
                SELECT w.id, w.name, w.owner_id AS "ownerId", COUNT(m.user_id) AS "memberCount"
                FROM workspaces w JOIN workspace_members m ON m.workspace_id = w.id
                WHERE w.id = ? GROUP BY w.id
                """, scope.id());
    }

    @GetMapping("/invitations")
    public List<Map<String, Object>> invitations(@AuthenticationPrincipal Jwt jwt) {
        return jdbc.queryForList("""
                SELECT i.id, w.id AS "teamId", w.name AS "teamName", u.name AS "invitedByName"
                FROM team_invitations i JOIN workspaces w ON w.id = i.workspace_id JOIN users u ON u.id = i.invited_by
                WHERE i.invitee_id = ? ORDER BY i.created_at DESC
                """, Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/{teamId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> invite(@PathVariable long teamId, @Valid @RequestBody InviteRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        requireOwner(teamId, jwt);
        List<Long> users = jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, request.email().trim().toLowerCase(Locale.ROOT));
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No registered account uses that email.");
        long invitee = users.getFirst();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workspace_members WHERE workspace_id = ? AND user_id = ?)", Boolean.class, teamId, invitee)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That account is already a team member.");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                INSERT INTO team_invitations(workspace_id, invitee_id, invited_by) VALUES (?, ?, ?)
                ON CONFLICT (workspace_id, invitee_id) DO NOTHING RETURNING id
                """, teamId, invitee, Long.valueOf(jwt.getSubject()));
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "An invitation is already pending for that account.");
        return rows.getFirst();
    }

    @PostMapping("/invitations/{id}/accept")
    @Transactional
    public Map<String, Object> accept(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        long userId = Long.parseLong(jwt.getSubject());
        List<Long> workspaces = jdbc.queryForList("DELETE FROM team_invitations WHERE id = ? AND invitee_id = ? RETURNING workspace_id",
                Long.class, id, userId);
        if (workspaces.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation was not found.");
        long teamId = workspaces.getFirst();
        jdbc.update("INSERT INTO workspace_members(workspace_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING", teamId, userId);
        return team(teamId, jwt);
    }

    @DeleteMapping("/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        if (jdbc.update("DELETE FROM team_invitations WHERE id = ? AND invitee_id = ?", id, Long.valueOf(jwt.getSubject())) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation was not found.");
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void removeMember(@PathVariable long teamId, @PathVariable long userId, @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> team = team(teamId, jwt);
        long ownerId = ((Number) team.get("ownerId")).longValue();
        long callerId = Long.parseLong(jwt.getSubject());
        if (callerId != ownerId && callerId != userId)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the team owner can remove other members.");
        if (userId == ownerId) throw new ResponseStatusException(HttpStatus.CONFLICT, "The team owner cannot leave the team.");
        if (jdbc.update("DELETE FROM workspace_members WHERE workspace_id = ? AND user_id = ?", teamId, userId) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team member was not found.");
    }

    private void requireOwner(long teamId, Jwt jwt) {
        if (((Number) team(teamId, jwt).get("ownerId")).longValue() != Long.parseLong(jwt.getSubject()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the team owner can invite members.");
    }

    public record TeamRequest(@NotBlank @Size(max = 100) String name) { }
    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email) { }
}
