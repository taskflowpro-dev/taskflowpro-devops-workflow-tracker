# TaskFlow Pro

A visual portfolio for understanding GitHub projects, reviewing repository health and delivery activity, and organizing the follow-up work for each project.

## Overview

TaskFlow Pro connects a Next.js dashboard, Spring Boot API, and PostgreSQL database. GitHub repositories and their saved analysis reports are the center of each workspace; projects group repositories and their follow-up tasks.

## Getting Started

### Run with Docker Compose

Requirements: Docker Engine with the Compose plugin.

From PowerShell, start all three containers in the current terminal:

```powershell
.\run.ps1
```

The script starts Docker Desktop if needed, creates a root `.env` with generated local credentials on first run, builds the images, and starts PostgreSQL, the API, and the frontend. Press **Ctrl+C** in that terminal to stop the containers while keeping database data. Open `http://localhost:3000`; the API is available at `http://localhost:8081`. Database files live in the `postgres_data` named volume. `docker compose down -v` also deletes that database volume.

For manual Compose use, copy `.env.example` to `.env` and set `DATABASE_PASSWORD` and a `JWT_SECRET` of at least 32 characters. Start and stop the containers with:

```powershell
docker compose up --build -d # Build and start the services
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

- Java 25 LTS
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

Migration V5 retains existing records with their creator (`created_by`, `deployed_by`, or `added_by`). If a task previously used another creator's project, a private project with the same name is created for the task owner, without copying the other creator's description. Existing assignments to other users are cleared.

### Team workspaces

The sidebar **Team** link opens `/teams`, where you can create a team, open a team you belong to, or respond to an invitation. A team opens at `/teams/{id}` and has its own tasks, projects, deployments, and repository reports. `/dashboard` always opens your personal workspace.

Team owners invite registered accounts by email from the team dashboard. Invitations appear inside the recipient's Team page; no email is sent. Accepting an invitation adds membership, while declining it grants no access. All accepted members can work on shared records and assign tasks to other members. The owner can remove members, and members can leave. The owner cannot leave their own team. Removing membership revokes team access and clears that member's assignments in that team, while keeping shared contributions and personal records.

Workspace data endpoints accept an optional `teamId` query parameter. Without it, they use the authenticated account's personal workspace. Every team request checks membership, and record IDs must belong to the selected workspace. Project names and tracked repositories are unique within a workspace.

Migration V6 adds explicit workspace IDs and assigns all existing records to their owner's personal workspace. It does not move any personal data into teams. New teams start empty.

Migration V7 links each saved repository report to its workspace project. Older reports remain available and can be linked from their report's project selector.

### Workspace checks

The API isolation and migration tests require a dedicated PostgreSQL test database. They run only when `TEST_DATABASE_URL` is set; use a test database rather than an application database. From `backend/`:

```powershell
$env:TEST_DATABASE_URL = 'jdbc:postgresql://localhost:5432/taskflow_test'
$env:TEST_DATABASE_USERNAME = 'postgres'
$env:TEST_DATABASE_PASSWORD = 'your-test-database-password'
.\mvnw.cmd '-Dtest=PrivateWorkspaceIntegrationTests,PrivateWorkspaceMigrationTests,TeamWorkspaceIntegrationTests,TeamWorkspaceMigrationTests' test
```

The checks cover personal isolation, team invitations and acceptance, shared CRUD, owner permissions, membership revocation, assignment restrictions, authentication, and upgrading both legacy shared data and personal records. The GitHub analyzer is mocked, so these tests make no GitHub requests.

### Dashboard repository analysis

From the authenticated dashboard, paste a repository URL such as `https://github.com/owner/repository`. The report is saved to PostgreSQL and linked to a project (a project is created automatically for a newly connected repository). It brings together repository metadata, languages and stack hints, the file tree, README outline, recent commits, contributors, open issues and pull requests, releases, and GitHub Actions runs.

TaskFlow Pro also inspects a bounded source archive for text-file and line counts, test-file hints, TODO/FIXME/HACK markers, and dependency samples. It checks for evidence of CI, tests, linting, container builds, deployments, and security scanning, then recommends project follow-up tasks. The signal percentage describes which checks were detected; it is not a code-quality or security certification. Source files are inspected in memory and are not stored. Archive inspection is limited to 32 MB compressed, 24 MB expanded, 4,000 files, and 768 KB per file; generated, vendor, and binary content is skipped. A report explains when inspection is partial or unavailable.

Public repositories work without a GitHub credential. Unauthenticated requests use GitHub's lower API rate limit. To analyze private repositories or increase that limit, configure a fine-grained GitHub token with read access to repository metadata, contents, issues, pull requests, and Actions. Put it in the root `.env` as `GITHUB_TOKEN=...`, then recreate the backend container with `docker compose up --build -d api`. Keep the token out of source control and do not share it in screenshots or logs.

Choose or change the project's link from the saved report. Add a recommendation or convert an open issue into a task; it will appear in that project's task board. Projects remain useful for repositories without imported tasks, and the task board supports regular manually created work too.

## Suggested Workflow

1. Connect a GitHub repository and review its project report.
2. Link it to the project space where its ongoing work belongs.
3. Turn relevant recommendations or GitHub issues into project tasks.
4. Track task progress and ownership in the workspace.
5. Review repository activity, pull requests, and Actions results as the code changes.
6. Record deployment or operational follow-up notes when useful.

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
