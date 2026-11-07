package com.taskflow.backend;

import com.taskflow.backend.service.GitHubRepositoryAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.import=",
        "debug=false",
        "logging.level.root=WARN",
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USERNAME:postgres}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
        "spring.flyway.locations=filesystem:../database/migrations",
        "app.jwt.secret=private-workspace-integration-test-secret-only"
})
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class TeamWorkspaceIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean GitHubRepositoryAnalyzer analyzer;
    private long alice;
    private long bob;
    private long charlie;

    @BeforeEach
    void setUp() {
        alice = user("Alice", "alice-team@example.test");
        bob = user("Bob", "bob-team@example.test");
        charlie = user("Charlie", "charlie-team@example.test");
        when(analyzer.parseUrl(anyString())).thenCallRealMethod();
        when(analyzer.analyze(anyString())).thenReturn(Map.of("fullName", "example/app", "description", "Team report", "language", "Java"));
    }

    @Test
    void invitationsGrantAccessOnlyAfterTheIntendedRecipientAccepts() throws Exception {
        long team = create(alice, "/api/teams", Map.of("name", "Platform team"));
        long invitation = invite(team, "BOB-TEAM@example.test");
        mvc.perform(get("/api/teams/invitations").with(as(charlie))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/teams/invitations").with(as(bob)))
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].teamName").value("Platform team"));
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(charlie))).andExpect(status().isNotFound());
        mvc.perform(get("/api/teams").with(as(bob))).andExpect(jsonPath("$.length()").value(0));
        for (String path : new String[]{"/api/users", "/api/tasks", "/api/projects", "/api/deployments", "/api/repositories"}) {
            mvc.perform(get(scope(path, team)).with(as(bob))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(team));
        mvc.perform(get("/api/teams/" + team).with(as(bob))).andExpect(jsonPath("$.memberCount").value(2));
        mvc.perform(get("/api/teams").with(as(bob))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/teams/invitations").with(as(bob))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(bob))).andExpect(status().isNotFound());
    }

    @Test
    void membersShareWorkOnlyInsideTheExplicitlySelectedTeam() throws Exception {
        long team = teamWithBob();
        long privateTask = create(alice, "/api/tasks", Map.of("title", "Alice private", "project", "Platform"));
        long teamTask = create(alice, scope("/api/tasks", team), Map.of("title", "Shared task", "project", "Platform", "assigneeId", bob));
        create(bob, scope("/api/tasks", team), Map.of("title", "Bob shared task", "project", "Platform"));
        long deployment = create(alice, scope("/api/deployments", team), release());
        long privateRepo = create(alice, "/api/repositories/analyze", repository());
        long teamRepo = create(alice, scope("/api/repositories/analyze", team), repository());
        assertNotEquals(privateRepo, teamRepo);
        assertEquals(teamRepo, create(bob, scope("/api/repositories/analyze", team), repository()));
        mvc.perform(get("/api/tasks").with(as(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(privateTask));
        mvc.perform(get("/api/tasks").with(as(bob))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(scope("/api/tasks", team)).with(as(bob))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(scope("/api/projects", team)).with(as(bob))).andExpect(jsonPath("$[0].taskCount").value(2));
        mvc.perform(get("/api/projects").with(as(alice))).andExpect(jsonPath("$[0].taskCount").value(1));
        mvc.perform(get(scope("/api/users", team)).with(as(bob))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(patch(scope("/api/tasks/" + teamTask, team)).with(as(bob)).contentType(APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(patch(scope("/api/deployments/" + deployment, team)).with(as(bob)).contentType(APPLICATION_JSON).content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mvc.perform(post(scope("/api/repositories/" + teamRepo + "/refresh", team)).with(as(bob))).andExpect(status().isOk());
        mvc.perform(delete(scope("/api/repositories/" + teamRepo, team)).with(as(bob))).andExpect(status().isNoContent());
        mvc.perform(get("/api/repositories").with(as(alice))).andExpect(jsonPath("$[0].id").value(privateRepo));
        mvc.perform(get(scope("/api/tasks", team)).with(as(charlie))).andExpect(status().isNotFound());
    }

    @Test
    void recordIdsCannotCrossWorkspaceBoundariesEvenForAMemberOfBothTeams() throws Exception {
        long first = create(alice, "/api/teams", Map.of("name", "First"));
        long second = create(alice, "/api/teams", Map.of("name", "Second"));
        long task = create(alice, scope("/api/tasks", first), Map.of("title", "First task", "project", "Platform"));
        long project = mapper.readTree(mvc.perform(get(scope("/api/projects", first)).with(as(alice))).andReturn().getResponse().getContentAsString()).get(0).path("id").asLong();
        long deployment = create(alice, scope("/api/deployments", first), release());
        long repo = create(alice, scope("/api/repositories/analyze", first), repository());
        mvc.perform(patch(scope("/api/tasks/" + task, second)).with(as(alice)).contentType(APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete(scope("/api/tasks/" + task, second)).with(as(alice))).andExpect(status().isNotFound());
        mvc.perform(delete(scope("/api/projects/" + project, second)).with(as(alice))).andExpect(status().isNotFound());
        mvc.perform(patch(scope("/api/deployments/" + deployment, second)).with(as(alice)).contentType(APPLICATION_JSON).content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isNotFound());
        clearInvocations(analyzer);
        mvc.perform(post(scope("/api/repositories/" + repo + "/refresh", second)).with(as(alice))).andExpect(status().isNotFound());
        verify(analyzer, never()).analyze(anyString());
        mvc.perform(delete(scope("/api/repositories/" + repo, second)).with(as(alice))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/tasks/" + task).with(as(alice))).andExpect(status().isNotFound());
        long privateTask = create(alice, "/api/tasks", Map.of("title", "Private", "project", "Platform"));
        mvc.perform(delete(scope("/api/tasks/" + privateTask, first)).with(as(alice))).andExpect(status().isNotFound());
        long privateWorkspace = jdbc.queryForObject("SELECT id FROM workspaces WHERE owner_id = ? AND kind = 'PERSONAL'", Long.class, alice);
        mvc.perform(get(scope("/api/tasks", privateWorkspace)).with(as(alice))).andExpect(status().isNotFound());
    }

    @Test
    void onlyOwnersInviteAndOnlyRecipientsCanDecline() throws Exception {
        long team = teamWithBob();
        mvc.perform(post("/api/teams/" + team + "/invitations").with(as(bob)).contentType(APPLICATION_JSON).content("{\"email\":\"charlie-team@example.test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/teams/" + team + "/invitations").with(as(alice)).contentType(APPLICATION_JSON).content("{\"email\":\"bob-team@example.test\"}"))
                .andExpect(status().isConflict());
        long invitation = invite(team, "charlie-team@example.test");
        mvc.perform(post("/api/teams/" + team + "/invitations").with(as(alice)).contentType(APPLICATION_JSON).content("{\"email\":\"charlie-team@example.test\"}"))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/teams/invitations/" + invitation).with(as(bob))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/teams/invitations/" + invitation).with(as(charlie))).andExpect(status().isNoContent());
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(charlie))).andExpect(status().isNotFound());
        mvc.perform(get("/api/teams/" + team).with(as(charlie))).andExpect(status().isNotFound());
        invite(team, "charlie-team@example.test");
        mvc.perform(post("/api/teams/" + team + "/invitations").with(as(alice)).contentType(APPLICATION_JSON).content("{\"email\":\"missing@example.test\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void removalRevokesAccessAndClearsOnlyAssignmentsInThatTeam() throws Exception {
        long team = teamWithBob();
        create(bob, "/api/tasks", Map.of("title", "Bob private", "project", "Private", "assigneeId", bob));
        long task = create(alice, scope("/api/tasks", team), Map.of("title", "Assigned to Bob", "project", "Platform", "assigneeId", bob));
        create(bob, scope("/api/tasks", team), Map.of("title", "Bob contribution", "project", "Platform"));
        long repo = create(alice, scope("/api/repositories/analyze", team), repository());
        mvc.perform(delete("/api/teams/" + team + "/members/" + alice).with(as(bob))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/teams/" + team + "/members/" + alice).with(as(alice))).andExpect(status().isConflict());
        mvc.perform(delete("/api/teams/" + team + "/members/" + bob).with(as(alice))).andExpect(status().isNoContent());
        assertEquals(null, jdbc.queryForMap("SELECT assignee_id FROM tasks WHERE id = ?", task).get("assignee_id"));
        mvc.perform(get("/api/tasks").with(as(bob))).andExpect(jsonPath("$[0].assigneeId").value(bob));
        mvc.perform(get(scope("/api/tasks", team)).with(as(alice))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(scope("/api/tasks", team)).with(as(bob))).andExpect(status().isNotFound());
        mvc.perform(patch(scope("/api/tasks/" + task, team)).with(as(bob)).contentType(APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
        clearInvocations(analyzer);
        mvc.perform(post(scope("/api/repositories/" + repo + "/refresh", team)).with(as(bob))).andExpect(status().isNotFound());
        verify(analyzer, never()).analyze(anyString());
        mvc.perform(get("/api/teams").with(as(bob))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void membersCanLeaveButCannotRemoveOtherMembersOrAssignOutsiders() throws Exception {
        long team = teamWithBob();
        long invitation = invite(team, "charlie-team@example.test");
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(charlie))).andExpect(status().isOk());
        mvc.perform(delete("/api/teams/" + team + "/members/" + charlie).with(as(bob))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/teams/" + team + "/members/" + bob).with(as(bob))).andExpect(status().isNoContent());
        mvc.perform(post(scope("/api/tasks", team)).with(as(alice)).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "No outsiders", "project", "Platform", "assigneeId", bob))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/teams/" + team).with(as(charlie))).andExpect(jsonPath("$.memberCount").value(2));
    }

    @Test
    void malformedWorkspaceSelectionsAndInvitationsReturnClientErrors() throws Exception {
        mvc.perform(post("/api/teams").with(as(alice)).contentType(APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        long team = create(alice, "/api/teams", Map.of("name", "Team"));
        mvc.perform(post("/api/teams/" + team + "/invitations").with(as(alice)).contentType(APPLICATION_JSON).content("{\"email\":\"invalid\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks?teamId=not-a-number").with(as(alice))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/teams").with(as(alice)).contentType(APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teamManagementRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teams/invitations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams").contentType(APPLICATION_JSON).content("{\"name\":\"Team\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/teams/1/members/1")).andExpect(status().isUnauthorized());
    }

    private long teamWithBob() throws Exception {
        long team = create(alice, "/api/teams", Map.of("name", "Platform team"));
        long invitation = invite(team, "bob-team@example.test");
        mvc.perform(post("/api/teams/invitations/" + invitation + "/accept").with(as(bob))).andExpect(status().isOk());
        return team;
    }

    private long invite(long team, String email) throws Exception { return create(alice, "/api/teams/" + team + "/invitations", Map.of("email", email)); }
    private String scope(String path, long team) { return path + "?teamId=" + team; }
    private RequestPostProcessor as(long userId) { return jwt().jwt(builder -> builder.subject(String.valueOf(userId))); }
    private long user(String name, String email) {
        return jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES (?,?, 'unused-test-hash') RETURNING id", Long.class, name, email);
    }
    private long create(long userId, String path, Map<String, ?> body) throws Exception {
        String response = mvc.perform(post(path).with(as(userId)).contentType(APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("id").asLong();
    }
    private Map<String, String> release() { return Map.of("service", "api", "environment", "staging", "version", "v1"); }
    private Map<String, String> repository() { return Map.of("url", "https://github.com/example/app"); }
}
