package com.taskflow.backend;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class TeamWorkspaceMigrationTests {
    @Test
    void existingPersonalRecordsRemainPrivateWhenExplicitWorkspacesAreAdded() {
        String schema = "team_workspace_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE SCHEMA " + schema);
        try {
            DriverManagerDataSource source = new DriverManagerDataSource(url, username, password);
            Properties properties = new Properties();
            properties.setProperty("currentSchema", schema);
            source.setConnectionProperties(properties);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("filesystem:../database/migrations").target("5").load().migrate();
            long alice = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES ('Alice','alice@migration.test','unused') RETURNING id", Long.class);
            long bob = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES ('Bob','bob@migration.test','unused') RETURNING id", Long.class);
            for (long user : new long[]{alice, bob}) {
                jdbc.update("INSERT INTO projects(name,description,created_by) VALUES ('Platform',?,?)", "Notes " + user, user);
                jdbc.update("INSERT INTO tasks(title,project,created_by,assignee_id) VALUES ('Personal task','Platform',?,?)", user, user);
                jdbc.update("INSERT INTO deployments(service,environment,version,deployed_by) VALUES ('api','staging','v1',?)", user);
                jdbc.update("INSERT INTO tracked_repositories(owner,repository,url,full_name,last_analysis,added_by) VALUES ('example','app','https://github.com/example/app','example/app','{}',?)", user);
            }
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("filesystem:../database/migrations").load().migrate();
            long aliceWorkspace = jdbc.queryForObject("SELECT id FROM workspaces WHERE owner_id = ? AND kind = 'PERSONAL'", Long.class, alice);
            long bobWorkspace = jdbc.queryForObject("SELECT id FROM workspaces WHERE owner_id = ? AND kind = 'PERSONAL'", Long.class, bob);
            assertNotEquals(aliceWorkspace, bobWorkspace);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workspaces WHERE kind = 'TEAM'", Integer.class));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM workspace_members", Integer.class));
            for (String table : new String[]{"projects", "tasks", "deployments", "tracked_repositories"}) {
                assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?", Integer.class, aliceWorkspace));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?", Integer.class, bobWorkspace));
            }
            assertEquals("Notes " + alice, jdbc.queryForObject("SELECT description FROM projects WHERE workspace_id = ?", String.class, aliceWorkspace));
            assertEquals(bob, jdbc.queryForObject("SELECT assignee_id FROM tasks WHERE workspace_id = ?", Long.class, bobWorkspace));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE tasks SET assignee_id = ? WHERE workspace_id = ?", alice, bobWorkspace));
        } finally { admin.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
}
