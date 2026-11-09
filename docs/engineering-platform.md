# Engineering intelligence platform

## Start and upgrade

Run `./run.sh` from Bash on Ubuntu/Fedora or Git Bash on Windows. It rebuilds the
API and web images. Flyway applies V8 on API startup; V1–V7 are unchanged. Keep a
normal database backup before upgrading an existing installation. Authentication,
project/task/release contracts, personal workspaces and accepted team membership
continue to use the existing APIs and WorkspaceAccess rules.

Refresh old repository reports to get schema version 3: inspected module profiles,
package-manager/lockfile evidence, runtime hints and inspected configuration paths.
No repository scripts are executed and raw archive source is not retained.

## Product areas

- Overview summarizes actual saved reports, projects and tasks, with per-practice
  bars and the latest returned commits. Unknown signals are excluded from averages.
- Repository Analyzer preserves metadata, stack, bounded source inspection,
  workflow evidence, issue import, recommendations-as-tasks and project linking.
- AI DevOps Architect and Implementation Guides generate a prioritized roadmap
  from saved checks. Steps, validation instructions and troubleshooting are saved.
  Synchronizing evidence preserves completion; completion is self-reported.
- Workflow Builder generates versioned review drafts for Java/Maven, Gradle with
  an inspected wrapper, Node/Next.js, Python, Docker, Dependabot, production Compose,
  registry publishing, and manual VPS release workflows. Select an inspected
  module for a monorepo Dockerfile. Supported package managers are detected from
  lockfile paths. Unknown runtime versions are labeled proposed defaults.
- Deployment Center saves VPS/managed-cloud plans, implementation commands and
  review status. Existing release records remain available below the advisory.
- Engineering views display known test-file/debt hints, dependency samples,
  security configuration, Actions runs and pull requests. Coverage and CVEs are
  unknown; the platform has not run a coverage or vulnerability scanner.
- Activity History records intelligence actions. Account supports display-name
  changes; Preferences stores compact spacing and reduced motion per account.
  Integration status exposes capability configuration without credential values.

## Optional local AI

The default assistant is **rules-based evidence guidance**, explicitly labeled in
its UI and saved messages. It is useful without a model, but is not an LLM.

To enable real AI answers, install a suitable model in your own Ollama instance,
then configure the API environment:

```dotenv
OLLAMA_MODEL=your-installed-model-name
OLLAMA_BASE_URL=http://localhost:11434
```

For Docker Compose, omit `OLLAMA_BASE_URL` to use the configured
`http://host.docker.internal:11434` default. The backend container must be able to
reach the Ollama server; a host service listening only on loopback may require a
private, firewall-restricted interface or an Ollama container on the same network.
Do not expose an unauthenticated Ollama endpoint to the public internet. Restart
and rebuild the API after changing environment values.

The implementation uses Ollama's non-streaming `/api/chat` endpoint, sends only
bounded repository context plus the last twelve messages, and provides no tools.
Repository context and chat history are treated as untrusted data. Provider
failures leave the message unsaved and surface a retryable error. The configured
model's availability is checked on use, not claimed by the configuration badge.
Conversations are shared within their selected workspace and survive refreshes.

Reference: https://docs.ollama.com/api/chat

## GitHub authorization and credentials

Public repositories work without a token. Keep `GITHUB_TOKEN` exclusively on the
backend, in environment/secret-manager configuration. Do not put it in any
`NEXT_PUBLIC_` value. No new database credential store is introduced, and secret
values are not collected by the integration UI or generated configuration forms.
If a future integration stores credentials in the database it must add encrypted
storage and a key-management policy before doing so.

A server-wide GitHub token must not let every registered account analyze all its
private repositories. An administrator must grant each private repository to an
explicit workspace using a comma-separated backend setting:

```dotenv
GITHUB_PRIVATE_WORKSPACE_GRANTS=12:owner/private-repo,27:owner/another-repo
```

Workspace IDs are the `workspaces.id` values managed by the administrator. Accepted
team membership or personal ownership is still required for every access. Tokens
should have only the read scopes needed for metadata, contents, issues, pull
requests and Actions. Optional endpoint permissions and GitHub rate limits can
limit a report. Private reports created before this upgrade retain their existing
workspace ownership; administrators should audit historical imports if a shared
token was previously used without per-workspace grants.

## Configuration and deployment safety

Generated files are persisted drafts, with analysis timestamps, notes, required
secret **names** and static validation results. Files can be copied/downloaded;
no existing workflow is overwritten. YAML is parsed with a safe loader and limits;
duplicate keys, obvious credential values and privileged pull-request triggers
are rejected. This is not a substitute for actionlint, Compose validation, image
builds or actual test runs. Actions are generated with version tags and a warning
to pin reviewed commit SHAs before production.

The release workflow is a manually triggered draft. It references an approval
protected GitHub environment, validates image digests, requires verified SSH host
keys and uses secret references. Configure required reviewers and real post-release
health checks before enabling it. The application never dispatches workflows,
creates GitHub branches/PRs, provisions infrastructure or executes deployments.
Plans marked reviewed do not imply a successful deployment.

See https://docs.github.com/en/actions/reference/security/secure-use for workflow
hardening and https://docs.docker.com/compose/how-tos/production/ for production
Compose guidance.

API request limits are per process: 20 compute-changing requests/minute and 180
other requests/minute per authenticated user (or remote address for unauthenticated
requests). Use a shared gateway limiter for multiple API replicas. No forwarded
client-IP header is trusted by the application limiter.

## New API contracts

All intelligence routes require the existing bearer JWT. Repository and workspace
routes accept optional `teamId`; IDs are resolved in that scope, never globally.

| Route under `/api/intelligence` | Methods | Behavior |
| --- | --- | --- |
| `/repositories/{id}/roadmap` | GET, POST | Read / synchronize recommendations and steps |
| `/recommendations/{id}/steps/{stepId}` | PATCH | Save `{completed: boolean}` |
| `/repositories/{id}/configurations` | GET, POST | Read / generate drafts with kind and stage options |
| `/repositories/{id}/configurations/{draftId}/validate` | POST | Re-run static validation |
| `/repositories/{id}/environments` | GET, POST | Read / create a named deployment plan |
| `/repositories/{id}/environments/{planId}` | PATCH | Set PLANNED or REVIEWED; never DEPLOYED |
| `/repositories/{id}/conversations` | GET, POST | Read / create workspace conversations |
| `/repositories/{id}/conversations/{chatId}/messages` | GET, POST | Read / send a contextual message |
| `/activity` | GET | Latest 100 workspace intelligence actions |
| `/account` | PATCH | Update the authenticated account's display name |
| `/preferences` | GET, PUT | Account-specific compactMode / reduceMotion |
| `/integrations` | GET | Read-only provider/capability status, without credentials |

Configuration requests use `kind`: `workflow`, `docker`, `security`, `deployment`
or `release`; optional booleans `tests`, `lint`, `security`, `container`, `publish`;
and `profileIndex` for a single inspected Docker module. Drafts are limited to
64 KiB; prompts to 4,000 characters and responses to 24,000 characters. Repository
child tables have composite repository/workspace foreign keys and cascade on
repository deletion.

## Validation

Frontend:

```bash
cd frontend
npm ci
npm test
npm run build
npm audit --omit=dev
```

Backend checks use a **dedicated** PostgreSQL test database, never your app data:

```bash
cd backend
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/taskflow_test
export TEST_DATABASE_USERNAME=your_test_user
export TEST_DATABASE_PASSWORD=your_test_password
bash ./mvnw \
  -Dspring.config.import= \
  -Dspring.datasource.url="$TEST_DATABASE_URL" \
  -Dspring.datasource.username="$TEST_DATABASE_USERNAME" \
  -Dspring.datasource.password="$TEST_DATABASE_PASSWORD" \
  -Dapp.jwt.secret=local-test-secret-with-at-least-32-characters \
  test
```

Browser checks use an isolated running API/frontend and an account with a connected
repository. They create real roadmap, conversation, draft, project, deployment-plan
and preference records in that account. Use only a test account:

```bash
cd frontend
E2E_BASE_URL=http://localhost:3100 \
E2E_EMAIL=your_test_account \
E2E_PASSWORD=your_test_password \
npm run test:e2e
```

Install the Playwright Chromium browser, or set `E2E_CHROME_PATH` to an existing
Chrome executable. Screenshots and traces are test artifacts, not product data.
