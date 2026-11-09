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
class PrivateWorkspaceIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean GitHubRepositoryAnalyzer analyzer;
    private long alice;
    private long bob;

    @BeforeEach
    void createUsers() {
        alice = user("Alice", "alice-private@example.test");
        bob = user("Bob", "bob-private@example.test");
        when(analyzer.parseUrl(anyString())).thenCallRealMethod();
        when(analyzer.analyze(anyString())).thenReturn(report("Original report"));
    }

    @Test
    void listsOnlyTheAuthenticatedUsersDataAndCounts() throws Exception {
        create(alice, "/api/tasks", Map.of("title", "Alice task", "project", "Platform"));
        create(bob, "/api/tasks", Map.of("title", "Bob task", "project", "Platform"));
        create(bob, "/api/tasks", Map.of("title", "Bob second task", "project", "Platform"));
        long deployment = create(alice, "/api/deployments", release("Alice service"));
        create(bob, "/api/deployments", release("Bob service"));
        long repository = create(alice, "/api/repositories/analyze", repository());
        create(bob, "/api/repositories/analyze", repository());

        mvc.perform(get("/api/tasks").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alice task"));
        mvc.perform(get("/api/projects").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name == 'Platform')].taskCount").value(org.hamcrest.Matchers.contains(1)));
        mvc.perform(get("/api/projects").with(as(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].taskCount").value(2));
        mvc.perform(get("/api/deployments").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(deployment));
        mvc.perform(get("/api/repositories").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(repository));
        mvc.perform(get("/api/users").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].email").value("alice-private@example.test"));
    }

    @Test
    void anotherAccountStartsWithAnEmptyWorkspaceEvenWithASpoofedUserParameter() throws Exception {
        create(alice, "/api/tasks", Map.of("title", "Private task", "project", "Private project"));
        create(alice, "/api/deployments", release("Private service"));
        create(alice, "/api/repositories/analyze", repository());
        for (String path : new String[]{"/api/tasks", "/api/projects", "/api/deployments", "/api/repositories"}) {
            mvc.perform(get(path).param("userId", String.valueOf(alice)).with(as(bob)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void taskIdsDoNotGrantAnotherUserWriteAccess() throws Exception {
        long task = create(alice, "/api/tasks", Map.of("title", "Private task", "project", "Platform"));
        mvc.perform(patch("/api/tasks/" + task).with(as(bob)).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/tasks/" + task).with(as(bob))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/tasks/" + task).with(as(alice)).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"DONE\",\"priority\":\"HIGH\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(delete("/api/tasks/" + task).with(as(alice))).andExpect(status().isNoContent());
    }

    @Test
    void projectNamesAndDeleteGuardsArePrivate() throws Exception {
        long aliceProject = create(alice, "/api/projects", Map.of("name", "Platform", "description", "Alice notes"));
        long bobProject = create(bob, "/api/projects", Map.of("name", "Platform", "description", "Bob notes"));
        assertNotEquals(aliceProject, bobProject);
        create(bob, "/api/tasks", Map.of("title", "Bob task", "project", "Platform"));
        mvc.perform(delete("/api/projects/" + aliceProject).with(as(bob))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/projects/" + bobProject).with(as(bob))).andExpect(status().isConflict());
        mvc.perform(delete("/api/projects/" + aliceProject).with(as(alice))).andExpect(status().isNoContent());
        mvc.perform(get("/api/projects").with(as(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("Bob notes"));
    }

    @Test
    void privateTasksCannotBeAssignedToOtherUsers() throws Exception {
        mvc.perform(post("/api/tasks").with(as(alice)).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", "Private task", "project", "New project", "assigneeId", bob))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/projects").with(as(alice))).andExpect(jsonPath("$.length()").value(0));
        long task = create(alice, "/api/tasks", Map.of("title", "Private task", "project", "Platform", "assigneeId", alice));
        mvc.perform(patch("/api/tasks/" + task).with(as(alice)).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("assigneeId", bob, "setAssignee", true))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks").with(as(alice))).andExpect(jsonPath("$[0].assigneeId").value(alice));
        mvc.perform(patch("/api/tasks/" + task).with(as(alice)).contentType(APPLICATION_JSON)
                        .content("{\"assigneeId\":null,\"setAssignee\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/tasks").with(as(alice))).andExpect(jsonPath("$[0].assigneeId").isEmpty());
    }

    @Test
    void deploymentIdsDoNotGrantAnotherUserWriteAccess() throws Exception {
        long deployment = create(alice, "/api/deployments", release("Alice service"));
        create(bob, "/api/deployments", release("Bob service"));
        assertEquals(alice, jdbc.queryForObject("SELECT deployed_by FROM deployments WHERE id = ?", Long.class, deployment));
        mvc.perform(patch("/api/deployments/" + deployment).with(as(bob)).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/deployments/" + deployment).with(as(alice)).contentType(APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(get("/api/deployments").with(as(bob))).andExpect(jsonPath("$[0].status").value("DEPLOYED"));
    }

    @Test
    void repositoryUpsertRefreshAndDeleteStayWithinTheOwnersWorkspace() throws Exception {
        long aliceRepository = create(alice, "/api/repositories/analyze", repository());
        long bobRepository = create(bob, "/api/repositories/analyze", repository());
        assertNotEquals(aliceRepository, bobRepository);
        assertEquals(aliceRepository, create(alice, "/api/repositories/analyze", repository()));
        clearInvocations(analyzer);
        mvc.perform(post("/api/repositories/" + aliceRepository + "/refresh").with(as(bob)))
                .andExpect(status().isNotFound());
        verify(analyzer, never()).analyze(anyString());
        mvc.perform(delete("/api/repositories/" + aliceRepository).with(as(bob))).andExpect(status().isNotFound());
        when(analyzer.analyze(anyString())).thenReturn(report("Alice refreshed report"));
        mvc.perform(post("/api/repositories/" + aliceRepository + "/refresh").with(as(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(aliceRepository));
        mvc.perform(get("/api/repositories").with(as(bob)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].description").value("Original report"));
        mvc.perform(delete("/api/repositories/" + aliceRepository).with(as(alice))).andExpect(status().isNoContent());
        mvc.perform(get("/api/repositories").with(as(bob))).andExpect(jsonPath("$[0].id").value(bobRepository));
    }

    @Test
    void privateEndpointsRequireAuthentication() throws Exception {
        for (String path : new String[]{"/api/users", "/api/tasks", "/api/projects", "/api/deployments", "/api/repositories"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/tasks/1")).andExpect(status().isUnauthorized());
    }

    private long user(String name, String email) {
        return jdbc.queryForObject("INSERT INTO users(name, email, password) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, email, "unused-test-password-hash");
    }

    private RequestPostProcessor as(long userId) {
        return jwt().jwt(builder -> builder.subject(String.valueOf(userId)));
    }

    private long create(long userId, String path, Map<String, ?> body) throws Exception {
        String response = mvc.perform(post(path).with(as(userId)).contentType(APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("id").asLong();
    }

    private Map<String, String> release(String service) {
        return Map.of("service", service, "environment", "staging", "version", "v1");
    }

    private Map<String, String> repository() {
        return Map.of("url", "https://github.com/example/app");
    }

    private Map<String, Object> report(String description) {
        return Map.of("fullName", "example/app", "description", description, "language", "Java");
    }
}
