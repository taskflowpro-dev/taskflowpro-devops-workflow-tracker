# TaskFlow Pro frontend

Next.js App Router, React, TypeScript and Lucide, with a shared bright design system.
Authentication and every persistent workspace action use the Spring Boot API.

For all services together, run `./run.sh` from the repository root (Git Bash on
Windows). See the [project README](../README.md) and
[engineering platform guide](../docs/engineering-platform.md).

For local frontend development:

```bash
npm ci
NEXT_PUBLIC_API_URL=http://localhost:8081 npm run dev
```

The API origin is embedded during production builds. Rebuild after changing it.
The default is `http://localhost:8081`.

Workspace views use URL hashes under `/dashboard` and `/teams/{id}`. The sidebar
uses browser hash navigation so view state, direct links and back navigation agree.
Repository-specific intelligence views retain the selected workspace scope and
selected repository. Focused components live in `src/components/intelligence/`.

```bash
npm test           # Critical API-backed interactions and error states
npm run build     # Production compilation and TypeScript checks
npm audit --omit=dev
npm run test:e2e   # Requires isolated servers/account; see platform guide
```

The optional model provider is configured only on the backend. The frontend never
receives GitHub credentials. Without a model, the assistant labels its answers as
rules-based repository guidance. No metrics, chat completions or deployments are
fabricated for the UI.
