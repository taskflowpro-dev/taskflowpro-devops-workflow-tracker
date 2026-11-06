# TaskFlow Pro

A centralized workspace for tracking DevOps workflows, tasks, releases, and operational improvements.

## Overview

TaskFlow Pro gives each account a private workspace for organizing work, with Spring Boot authentication, PostgreSQL persistence, and a Next.js dashboard.

## Getting Started

### Run with Docker Compose

Requirements: Docker Engine with the Compose plugin.

```powershell
Copy-Item .env.example .env
```

Edit the root `.env` and set `DATABASE_PASSWORD` and `JWT_SECRET`. `JWT_SECRET` must be at least 32 characters. `DATABASE_URL` uses `localhost` for local backend runs; Compose overrides it to use the `db` service inside Docker. Then build and start the stack:

```powershell
docker compose up --build -d
docker compose ps
```

Open `http://localhost:3000`; the API is available at `http://localhost:8081`. To view logs, run `docker compose logs -f`; to stop the services while keeping database data, run `docker compose down`. Database files live in the `postgres_data` named volume. `docker compose down -v` also deletes that database volume.

Start and stop the containers manually with:

```powershell
docker compose up -d       # Start the services
docker compose stop        # Stop them; keep containers and database data
docker compose start       # Start the stopped containers again
docker compose down        # Stop and remove containers; keep database data
```

Use `docker compose up --build -d` after changing application code or Dockerfiles to rebuild images and start the services. Services have no automatic restart policy, so they stay stopped when Docker or the computer restarts until you start them again.

The frontend API URL is embedded into the Next.js image during its build. If you change `NEXT_PUBLIC_API_URL`, rebuild the web image with `docker compose build web`.

Clone the repository locally:

```bash
git clone https://github.com/taskflowpro-dev/taskflowpro-devops-workflow-tracker.git
cd taskflowpro-devops-workflow-tracker
```

### Requirements

- Java 21
- PostgreSQL with a `taskflow_db` database and an application user
- Node.js 20 or newer

### Configure and start the backend

Backend configuration is loaded from `backend/.env` (ignored by Git) when running Spring Boot locally. The root `.env.example` lists the shared configuration values. Set `DATABASE_PASSWORD` to the PostgreSQL password for `taskflow_app`. `JWT_SECRET` must be a random secret of at least 32 bytes. Flyway reads migrations from the packaged application; Hibernate validates the schema and does not create tables.

PowerShell example:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

For local development, keep a `backend/.env` file with local database settings; copy the root `.env.example` into `backend/.env` and adjust `DATABASE_URL` to use `localhost`. Start Spring Boot with `backend/` as the working directory.

The API listens on `http://localhost:8081`.

### Start the frontend

```powershell
cd frontend
npm install
npm run dev
```

Open `http://localhost:3000`. Set `NEXT_PUBLIC_API_URL` if the API runs on a different origin. Registration is available at `/signup`; login is at `/login`; the authenticated dashboard is at `/dashboard`.

### Authentication API

- `POST /api/auth/register` — creates an account and returns safe user details.
- `POST /api/auth/login` — verifies credentials and returns a signed bearer token.
- `GET /api/users/me` — returns the authenticated user's safe profile.

Registration and login accept JSON with `name`, `email`, and `password` (registration), or `email` and `password` (login). Protected requests use `Authorization: Bearer <token>`.

### Private workspaces

The dashboard shows only the authenticated user's projects, tasks, deployments, and saved repository reports. API reads and writes enforce ownership using the authenticated token. Requests to modify another user's records return `404`. The workspace user list contains only the account owner; private tasks can be unassigned or assigned to that owner. Project names and repository tracking are unique per account.

Migration V5 retains existing records with their creator (`created_by`, `deployed_by`, or `added_by`). If a task previously used another creator's project, a private project with the same name is created for the task owner, without copying the other creator's description. Existing assignments to other users are cleared. Team sharing is a separate feature and is not enabled by this migration.

### Private workspace checks

The API isolation and migration tests require a dedicated PostgreSQL test database. They run only when `TEST_DATABASE_URL` is set; use a test database rather than an application database. From `backend/`:

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:5432/taskflow_test'
$env:TEST_DATABASE_USERNAME = 'postgres'
$env:TEST_DATABASE_PASSWORD = 'your-test-database-password'
.\mvnw.cmd '-Dtest=PrivateWorkspaceIntegrationTests,PrivateWorkspaceMigrationTests' test
```

The checks cover two-account read/write isolation, private project names and repository reports, assignment restrictions, authentication, and upgrading legacy shared records. The GitHub analyzer is mocked, so these tests make no GitHub requests.

### Dashboard repository analysis

From the authenticated dashboard, paste a public GitHub repository URL such as `https://github.com/owner/repository`. TaskFlow Pro saves the repository and analyzes its metadata, open issues, GitHub Actions workflows, and latest workflow run. The report checks for CI triggers, tests, linting, container builds, deployments, and security scanning, then suggests backlog tasks that can be added to the Kanban board. Private repositories are not supported until a GitHub credential integration is configured.

## Suggested Workflow

1. Create or update a task for the work being planned.
2. Document the workflow, dependencies, and acceptance criteria.
3. Implement changes in a feature branch.
4. Run the relevant checks and validation steps.
5. Open a pull request for review.
6. Record deployment or operational follow-up notes.

## Repository Structure

The repository is currently being initialized. Suggested directories include:

```text
.
├── .github/       # Issue templates, pull request templates, and workflows
├── docs/          # Workflow and operational documentation
├── workflows/     # DevOps workflow definitions
└── README.md      # Project documentation
```

## Contributing

1. Create a feature branch from `main`.
2. Keep changes focused and document workflow-related updates.
3. Validate changes before opening a pull request.
4. Include testing or deployment notes in the pull request description.

## License

A license has not yet been selected for this repository.
