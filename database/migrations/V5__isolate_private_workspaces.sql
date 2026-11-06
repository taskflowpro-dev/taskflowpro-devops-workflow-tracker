-- Project names and tracked repositories are unique within each user's workspace.
ALTER TABLE projects DROP CONSTRAINT projects_name_key;
ALTER TABLE projects ADD CONSTRAINT uk_projects_creator_name UNIQUE (created_by, name);

-- Keep every task with its creator, including tasks previously using another
-- user's project. Only the project name is copied; private descriptions stay put.
INSERT INTO projects (name, created_by)
SELECT DISTINCT project, created_by FROM tasks
ON CONFLICT (created_by, name) DO NOTHING;

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_private_project
    FOREIGN KEY (created_by, project) REFERENCES projects (created_by, name);

-- Cross-user assignments belonged to the old shared workspace.
UPDATE tasks SET assignee_id = NULL
WHERE assignee_id IS NOT NULL AND assignee_id <> created_by;
ALTER TABLE tasks ADD CONSTRAINT ck_tasks_private_assignee
    CHECK (assignee_id IS NULL OR assignee_id = created_by);

ALTER TABLE tracked_repositories DROP CONSTRAINT uk_tracked_repositories_owner_repo;
ALTER TABLE tracked_repositories ADD CONSTRAINT uk_tracked_repositories_user_owner_repo
    UNIQUE (added_by, owner, repository);

CREATE INDEX idx_tasks_creator_updated ON tasks (created_by, updated_at DESC, id DESC);
CREATE INDEX idx_deployments_user_date ON deployments (deployed_by, deployed_at DESC, id DESC);
