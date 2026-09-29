# TaskFlow Pro

A centralized workspace for tracking DevOps workflows, tasks, releases, and operational improvements.

## Overview

TaskFlow Pro is a team workspace for organizing work. The initial application includes Spring Boot authentication, PostgreSQL persistence, and a Next.js dashboard shell.

## Getting Started

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

Backend configuration is loaded from `backend/.env` (ignored by Git). `backend/.env.example` lists the required variables. Set `DATABASE_PASSWORD` to the PostgreSQL password for `taskflow_app`. `JWT_SECRET` must be a random secret of at least 32 bytes. Flyway reads migrations from the root `database/migrations/` folder; Hibernate validates the schema and does not create tables.

PowerShell example:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Copy `.env.example` to `.env` in `backend/` if the local env file is missing, then replace the database password and JWT secret with your own values. Start Spring Boot with `backend/` as the working directory so Flyway can resolve `../database/migrations`.

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
