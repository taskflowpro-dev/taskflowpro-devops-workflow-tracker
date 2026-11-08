ALTER TABLE tracked_repositories
    ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE SET NULL;

CREATE INDEX idx_tracked_repositories_project ON tracked_repositories(project_id);
