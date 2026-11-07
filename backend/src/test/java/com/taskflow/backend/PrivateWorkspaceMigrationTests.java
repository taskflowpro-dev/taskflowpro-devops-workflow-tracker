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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class PrivateWorkspaceMigrationTests {
    @Test
    void migratesSharedDataWithoutLosingRecordsOrCopyingPrivateDescriptions() {
        String schema = "private_workspace_migration_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("TEST_DATABASE_URL");
        String username = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
        JdbcTemplate admin = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        admin.execute("CREATE SCHEMA " + schema);
        try {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
            Properties properties = new Properties();
            properties.setProperty("currentSchema", schema);
            dataSource.setConnectionProperties(properties);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:../database/migrations").target("4").load().migrate();
            long alice = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES ('Alice','alice@test.invalid','unused') RETURNING id", Long.class);
            long bob = jdbc.queryForObject("INSERT INTO users(name,email,password) VALUES ('Bob','bob@test.invalid','unused') RETURNING id", Long.class);
            jdbc.update("INSERT INTO projects(name,description,created_by) VALUES ('Legacy','Alice private notes',?)", alice);
            jdbc.update("INSERT INTO tasks(title,project,created_by,assignee_id) VALUES ('Alice task','Legacy',?,?)", alice, alice);
            long bobTask = jdbc.queryForObject("INSERT INTO tasks(title,project,created_by,assignee_id) VALUES ('Bob task','Legacy',?,?) RETURNING id", Long.class, bob, alice);
            jdbc.update("INSERT INTO deployments(service,environment,version,deployed_by) VALUES ('api','staging','v1',?)", bob);
            jdbc.update("INSERT INTO tracked_repositories(owner,repository,url,full_name,last_analysis,added_by) VALUES ('example','app','https://github.com/example/app','example/app','{}',?)", alice);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("filesystem:../database/migrations").target("5").load().migrate();

            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Integer.class));
            assertEquals(bob, jdbc.queryForObject("SELECT created_by FROM tasks WHERE id = ?", Long.class, bobTask));
            assertNull(jdbc.queryForMap("SELECT assignee_id FROM tasks WHERE id = ?", bobTask).get("assignee_id"));
            assertEquals(alice, jdbc.queryForObject("SELECT assignee_id FROM tasks WHERE created_by = ?", Long.class, alice));
            assertEquals("Alice private notes", jdbc.queryForObject("SELECT description FROM projects WHERE created_by = ?", String.class, alice));
            assertEquals("", jdbc.queryForObject("SELECT description FROM projects WHERE created_by = ? AND name = 'Legacy'", String.class, bob));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM deployments WHERE deployed_by = ?", Integer.class, bob));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tracked_repositories WHERE added_by = ?", Integer.class, alice));
            jdbc.update("INSERT INTO tracked_repositories(owner,repository,url,full_name,last_analysis,added_by) VALUES ('example','app','https://github.com/example/app','example/app','{}',?)", bob);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM tracked_repositories", Integer.class));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO projects(name,created_by) VALUES ('Legacy',?)", bob));
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("UPDATE tasks SET assignee_id = ? WHERE id = ?", alice, bobTask));
            jdbc.update("INSERT INTO projects(name,created_by) VALUES ('Owner only',?)", alice);
            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("INSERT INTO tasks(title,project,created_by) VALUES ('Wrong owner','Owner only',?)", bob));
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
