package com.taskflow.backend.intelligence;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowGenerationService {
    public record Draft(String path, String content, Map<String, Object> metadata) { }
    public Draft generate(JsonNode analysis, String kind, boolean tests, boolean lint, boolean security, boolean container, boolean publish) {
        return generate(analysis, kind, tests, lint, security, container, publish, null);
    }
    public Draft generate(JsonNode analysis, String kind, boolean tests, boolean lint, boolean security, boolean container, boolean publish, Integer profileIndex) {
        if (kind.equals("security")) return security(analysis);
        if (kind.equals("release")) return release();
        List<JsonNode> profiles = new ArrayList<>();
        analysis.path("buildProfiles").forEach(p -> { if (p.path("inspected").asBoolean()) profiles.add(p); });
        if (kind.equals("docker")) {
            if (profileIndex != null) {
                if (profileIndex < 0 || profileIndex >= profiles.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an inspected module.");
                return docker(List.of(profiles.get(profileIndex)));
            }
            return docker(profiles);
        }
        if (!kind.equals("workflow")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported configuration kind.");
        if (profiles.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No inspected build profiles are available. Refresh analysis or configure the pipeline manually.");
        StringBuilder yaml = new StringBuilder("""
                # Draft: review module paths, runtime versions and action SHAs before applying.
                # Never overwrite an existing workflow without comparing it first.
                name: TaskFlow engineering checks
                on:
                  push:
                  pull_request:
                  workflow_dispatch:
                permissions:
                  contents: read
                concurrency:
                  group: taskflow-${{ github.workflow }}-${{ github.ref }}
                  cancel-in-progress: true
                jobs:
                """);
        List<String> notes = new ArrayList<>();
        int count = 0;
        for (JsonNode profile : profiles) {
            String directory = profile.path("directory").asString(".");
            if (!directory.matches("[A-Za-z0-9_./-]+") || List.of(directory.split("/")).contains("..")) continue;
            String ecosystem = profile.path("ecosystem").asString();
            if (!List.of("node", "maven", "gradle", "python").contains(ecosystem)) continue;
            yaml.append("  module-").append(++count).append(":\n    runs-on: ubuntu-latest\n    timeout-minutes: 20\n    defaults:\n      run:\n        working-directory: '").append(directory).append("'\n    steps:\n      - uses: actions/checkout@v4\n");
            String runtime = profile.path("runtime").asString(ecosystem.equals("node") ? "22" : "21");
            if (!runtime.matches("[0-9]{1,2}(\\.[0-9]{1,2})?")) runtime = "21";
            if (profile.path("runtimeEstimated").asBoolean()) notes.add(directory + ": runtime " + runtime + " is a proposed default; confirm compatibility.");
            switch (ecosystem) {
                case "node" -> {
                    String manager = profile.path("packageManager").asString("npm");
                    if (!List.of("npm", "pnpm", "yarn").contains(manager)) manager = "npm";
                    yaml.append("      - uses: actions/setup-node@v4\n        with:\n          node-version: '").append(runtime).append("'\n");
                    if (!manager.equals("npm")) yaml.append("      - name: Enable declared package manager\n        run: corepack enable\n");
                    String install = manager.equals("pnpm") ? "pnpm install --frozen-lockfile" : manager.equals("yarn") ? "yarn install --immutable" : profile.path("lockfileDetected").asBoolean() ? "npm ci" : "npm install";
                    yaml.append("      - name: Install dependencies\n        run: ").append(install).append("\n");
                    if (!profile.path("lockfileDetected").asBoolean()) notes.add(directory + ": no supported lockfile detected. Commit a lockfile and use a frozen install before production use.");
                    if (!manager.equals("npm")) notes.add(directory + ": confirm the declared " + manager + " version and install flags against your lockfile version.");
                    if (lint && hasScript(profile, "lint")) yaml.append("      - name: Lint\n        run: ").append(manager).append(" run lint\n");
                    if (tests && hasScript(profile, "test")) yaml.append("      - name: Test\n        run: ").append(manager).append(" test\n");
                    else if (tests) notes.add(directory + ": no test script inspected; add tests before making them a required gate.");
                    if (hasScript(profile, "build")) yaml.append("      - name: Build\n        run: ").append(manager).append(" run build\n");
                    if (security && manager.equals("npm")) yaml.append("      - name: Dependency audit\n        run: npm audit --audit-level=high\n");
                    else if (security) notes.add(directory + ": configure the security audit command for your " + manager + " version.");
                }
                case "maven", "gradle" -> {
                    yaml.append("      - uses: actions/setup-java@v4\n        with:\n          distribution: temurin\n          java-version: '").append(runtime).append("'\n");
                    if (ecosystem.equals("maven")) yaml.append("      - name: Verify Java module\n        run: ").append(profile.path("wrapperDetected").asBoolean() ? "bash ./mvnw -B verify" : "mvn -B verify").append(tests ? "" : " -DskipTests").append("\n");
                    else if (profile.path("wrapperDetected").asBoolean()) yaml.append("      - name: Verify Gradle module\n        run: bash ./gradlew build").append(tests ? "" : " -x test").append("\n");
                    else { yaml.append("      - name: Configure missing Gradle wrapper\n        run: |\n          echo 'REVIEW_REQUIRED: commit and review a Gradle wrapper before running this module'\n          exit 1\n"); notes.add(directory + ": no Gradle wrapper detected; this job deliberately fails until configured."); }
                    notes.add(directory + ": configure dedicated test database/environment secrets if integration tests need them.");
                }
                case "python" -> {
                    yaml.append("      - uses: actions/setup-python@v5\n        with:\n          python-version: '").append(runtime).append("'\n      - name: Install dependencies\n        run: ").append(profile.path("manifest").asString().endsWith("requirements.txt") ? "python -m pip install -r requirements.txt" : "python -m pip install .").append("\n");
                    if (tests && hasScript(profile, "test")) yaml.append("      - name: Test\n        run: python -m pytest\n");
                    if (lint) notes.add(directory + ": configure a Python lint tool before adding a lint command.");
                    if (security) yaml.append("      - name: Dependency audit\n        run: |\n          python -m pip install pip-audit\n          python -m pip_audit\n");
                }
                default -> { }
            }
        }
        if (count == 0) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "No supported, inspected build profile was found.");
        if (container || publish) {
            yaml.append("  container:\n    needs: [");
            for (int i = 1; i <= count; i++) yaml.append(i > 1 ? ", " : "").append("module-").append(i);
            yaml.append("]\n    runs-on: ubuntu-latest\n    timeout-minutes: 20\n    steps:\n      - uses: actions/checkout@v4\n      - name: Build reviewed Dockerfile\n        run: docker build --file Dockerfile.taskflow --tag app:review .\n");
            notes.add("Container job expects a reviewed Dockerfile.taskflow at the repository root. Generate or adapt it before running this job.");
        }
        if (publish) {
            yaml.append("""
                  publish:
                    if: github.event_name == 'push' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
                    needs: container
                    runs-on: ubuntu-latest
                    timeout-minutes: 20
                    environment: registry-review
                    permissions:
                      contents: read
                      packages: write
                    steps:
                      - uses: actions/checkout@v4
                      - name: Authenticate to GHCR
                        env:
                          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
                        run: printf '%s' "$GH_TOKEN" | docker login ghcr.io --username "$GITHUB_ACTOR" --password-stdin
                      - name: Publish immutable image
                        run: |
                          IMAGE="ghcr.io/${GITHUB_REPOSITORY,,}:$GITHUB_SHA"
                          docker build --file Dockerfile.taskflow --tag "$IMAGE" .
                          docker push "$IMAGE"
                """);
            notes.add("Configure required reviewers on the registry-review GitHub environment. Publishing runs only on default-branch pushes; no production deployment is performed.");
        }
        return new Draft(".github/workflows/taskflow-ci.yml", yaml.toString(), Map.of("notes", notes, "requiredSecrets", publish ? List.of("GITHUB_TOKEN (automatically provided by GitHub)") : List.of(), "existingWorkflows", analysis.path("workflows"), "overwrite", false));
    }
    private boolean hasScript(JsonNode profile, String name) {
        for (JsonNode script : profile.path("scripts")) if (name.equals(script.asString())) return true;
        return false;
    }
    private Draft docker(List<JsonNode> profiles) {
        if (profiles.size() != 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Docker generation requires one inspected module. For a monorepo, use the deployment plan and adapt a separate Dockerfile for each service.");
        JsonNode p = profiles.getFirst();
        String directory = p.path("directory").asString(".");
        if (!directory.matches("[A-Za-z0-9_./-]+") || List.of(directory.split("/")).contains("..")) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Review the module path manually.");
        String ecosystem = p.path("ecosystem").asString();
        String version = p.path("runtime").asString("21");
        if (!version.matches("[0-9]{1,2}(\\.[0-9]{1,2})?")) version = "21";
        String content;
        if (ecosystem.equals("maven")) content = "FROM maven:3.9-eclipse-temurin-" + version + " AS build\nWORKDIR /source\nCOPY " + directory + "/ ./\nRUN mvn -B package -DskipTests\nFROM eclipse-temurin:" + version + "-jre\nWORKDIR /app\nCOPY --from=build /source/target/*.jar app.jar\nUSER 10001:10001\nEXPOSE 8080\nENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n";
        else if (ecosystem.equals("node") && hasScript(p, "start")) content = "FROM node:" + version + "-alpine AS build\nWORKDIR /app\nCOPY " + directory + "/ ./\nRUN npm ci\n" + (hasScript(p, "build") ? "RUN npm run build\n" : "") + "ENV NODE_ENV=production\nUSER node\nEXPOSE 3000\nCMD [\"npm\", \"start\"]\n";
        else throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A safe runtime entry point was not detected. Use a deployment plan to review the start command manually.");
        return new Draft("Dockerfile.taskflow", "# Draft: review .dockerignore, build context, ports and runtime configuration.\n" + content,
                Map.of("notes", List.of("Add .dockerignore rules for .env, node_modules, .git and local credentials before building.", "Review exposed ports and external migration paths. Tests run separately in CI."), "requiredSecrets", List.of(), "overwrite", false));
    }
    private Draft security(JsonNode analysis) {
        StringBuilder yaml = new StringBuilder("version: 2\nupdates:\n");
        java.util.Set<String> entries = new java.util.LinkedHashSet<>();
        for (JsonNode profile : analysis.path("buildProfiles")) {
            String ecosystem = profile.path("ecosystem").asString();
            if (ecosystem.equals("node")) ecosystem = "npm";
            if (ecosystem.equals("python")) ecosystem = "pip";
            String directory = profile.path("directory").asString(".");
            if (!List.of("npm", "pip", "maven", "gradle").contains(ecosystem) || !directory.matches("[A-Za-z0-9_./-]+") || List.of(directory.split("/")).contains("..")) continue;
            String item = "  - package-ecosystem: '" + ecosystem + "'\n    directory: '" + (directory.equals(".") ? "/" : "/" + directory) + "'\n    schedule:\n      interval: weekly\n";
            if (entries.add(item)) yaml.append(item);
        }
        yaml.append("  - package-ecosystem: github-actions\n    directory: /\n    schedule:\n      interval: weekly\n");
        return new Draft(".github/dependabot.yml", yaml.toString(), Map.of("notes", List.of("Dependency updates are configuration evidence, not vulnerability scan results. Enable secret scanning and review branch protection in GitHub separately."), "requiredSecrets", List.of(), "overwrite", false));
    }
    private Draft release() {
        return new Draft(".github/workflows/taskflow-release.yml", """
                # REVIEW_REQUIRED: install the reviewed Compose configuration on the target host.
                # Require reviewers on the production environment before enabling this workflow.
                name: Reviewed VPS release
                on:
                  workflow_dispatch:
                    inputs:
                      image:
                        description: Immutable image reference including sha256 digest
                        required: true
                        type: string
                permissions:
                  contents: read
                concurrency:
                  group: taskflow-production
                  cancel-in-progress: false
                jobs:
                  release:
                    if: github.ref == format('refs/heads/{0}', github.event.repository.default_branch)
                    runs-on: ubuntu-latest
                    timeout-minutes: 15
                    environment: production
                    steps:
                      - name: Validate image reference
                        env:
                          IMAGE: ${{ inputs.image }}
                        run: |
                          [[ "$IMAGE" =~ ^[a-z0-9][a-z0-9./_-]+@sha256:[a-f0-9]{64}$ ]] || exit 1
                      - name: Configure approved SSH identity
                        env:
                          SSH_KEY: ${{ secrets.DEPLOY_SSH_KEY }}
                          KNOWN_HOSTS: ${{ secrets.DEPLOY_KNOWN_HOSTS }}
                        run: |
                          install -m 700 -d ~/.ssh
                          printf '%s\\n' "$SSH_KEY" > ~/.ssh/deploy_key
                          printf '%s\\n' "$KNOWN_HOSTS" > ~/.ssh/known_hosts
                          chmod 600 ~/.ssh/deploy_key ~/.ssh/known_hosts
                      - name: Release reviewed image
                        env:
                          IMAGE: ${{ inputs.image }}
                          DEPLOY_HOST: ${{ secrets.DEPLOY_HOST }}
                          DEPLOY_USER: ${{ secrets.DEPLOY_USER }}
                        run: |
                          [[ "$DEPLOY_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || exit 1
                          [[ "$DEPLOY_USER" =~ ^[a-z_][a-z0-9_-]*$ ]] || exit 1
                          ssh -i ~/.ssh/deploy_key -o StrictHostKeyChecking=yes "$DEPLOY_USER@$DEPLOY_HOST" \\
                            "cd /opt/taskflow && export APP_IMAGE='$IMAGE' && docker compose --env-file deploy/.env -f deploy/compose.taskflow.yaml pull app && docker compose --env-file deploy/.env -f deploy/compose.taskflow.yaml up -d app"
                      - name: Remove temporary identity
                        if: always()
                        run: rm -f ~/.ssh/deploy_key ~/.ssh/known_hosts
                """, Map.of("notes", List.of("Draft only. Set required reviewers on the GitHub production environment; workflow dispatch alone does not establish an approval policy.",
                        "Prepare /opt/taskflow/deploy on the Ubuntu host with reviewed Compose and server-only environment files. Verify the SSH host fingerprint through a trusted channel.",
                        "Configure private registry access on the host. Add an actual application health check and post-release smoke test before using this draft; a successful SSH command is not proof of healthy deployment.",
                        "Rollback uses the same workflow with the previous approved image digest. Use backward-compatible database migrations."),
                "requiredSecrets", List.of("DEPLOY_HOST", "DEPLOY_USER", "DEPLOY_SSH_KEY", "DEPLOY_KNOWN_HOSTS"), "overwrite", false));
    }
}
