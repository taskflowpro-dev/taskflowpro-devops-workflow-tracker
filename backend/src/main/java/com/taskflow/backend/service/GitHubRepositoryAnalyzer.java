package com.taskflow.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;

@Service
public class GitHubRepositoryAnalyzer {
    private static final Pattern OWNER_OR_REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final int MAX_TEXT_CHARS = 6000;
    private static final List<CheckDefinition> CHECKS = List.of(
            new CheckDefinition("ci", "Continuous integration", Pattern.compile("\\b(on:|push:|pull_request:|workflow_dispatch:)"), "Add a GitHub Actions workflow that validates pushes and pull requests.", "HIGH"),
            new CheckDefinition("tests", "Automated tests", Pattern.compile("\\b(test|pytest|mvn test|gradle test|go test|jest|vitest|playwright)\\b"), "Add automated tests and run them for every pull request.", "HIGH"),
            new CheckDefinition("lint", "Linting and formatting", Pattern.compile("\\b(lint|eslint|ruff|flake8|checkstyle|prettier|spotless)\\b"), "Add lint and formatting checks to catch issues before merge.", "MEDIUM"),
            new CheckDefinition("container", "Container build", Pattern.compile("docker/build-push-action|docker build|buildah|build-image"), "Build the container image in CI so Dockerfile changes are validated.", "MEDIUM"),
            new CheckDefinition("deploy", "Automated deployment", Pattern.compile("\\b(deploy|kubectl|helm|aws-actions|azure/webapps-deploy)\\b"), "Add a gated staging deployment after the build and test jobs pass.", "MEDIUM"),
            new CheckDefinition("security", "Security scanning", Pattern.compile("trivy|snyk|codeql|dependency-review|gitleaks|trufflehog|dependabot"), "Add dependency, secret, and container vulnerability scanning.", "HIGH")
    );
    private static final int MAX_ARCHIVE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ARCHIVE_SOURCE_BYTES = 24 * 1024 * 1024;
    private static final int MAX_SOURCE_FILE_BYTES = 768 * 1024;
    private static final int MAX_ARCHIVE_FILES = 4000;

    private final ObjectMapper mapper;
    private final String githubToken;
    @Value("${github.private-workspace-grants:}")
    private String privateWorkspaceGrants = "";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GitHubRepositoryAnalyzer(ObjectMapper mapper, @Value("${github.token:}") String githubToken) {
        this.mapper = mapper;
        this.githubToken = githubToken == null ? "" : githubToken.trim();
    }

    /** A shared server token must not grant every registered user access to its private repositories. */
    public void assertAccess(RepositoryRef ref, long workspaceId) {
        if (githubToken.isBlank()) return;
        JsonNode metadata = request("repos/" + ref.owner() + "/" + ref.name(), false);
        if (!metadata.path("private").asBoolean(false)) return;
        String grant = workspaceId + ":" + (ref.owner() + "/" + ref.name()).toLowerCase(Locale.ROOT);
        boolean allowed = java.util.Arrays.stream(privateWorkspaceGrants.split(",")).map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(grant::equals);
        if (!allowed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found or not authorized for this workspace.");
    }

    /** Builds a broad, evidence-based report from GitHub's repository, tree, language, README,
     * workflow, issue, pull request, commit, contributor, release, and Actions APIs. */
    public Map<String, Object> analyze(String repositoryUrl) {
        RepositoryRef ref = parseUrl(repositoryUrl);
        String root = "repos/" + ref.owner() + "/" + ref.name();
        JsonNode repository = request(root, false);
        JsonNode languages = request(root + "/languages", false);
        JsonNode tree = request(root + "/git/trees/" + encodePathSegment(repository.path("default_branch").asString("main")) + "?recursive=1", true);
        JsonNode readmeNode = request(root + "/readme", true);
        JsonNode commitsNode = request(root + "/commits?per_page=10", true);
        JsonNode contributorsNode = request(root + "/contributors?per_page=10", true);
        JsonNode issueNode = request(root + "/issues?state=open&per_page=30", true);
        JsonNode pullNode = request(root + "/pulls?state=open&per_page=20", true);
        JsonNode releaseNode = request(root + "/releases/latest", true);
        JsonNode runsNode = request(root + "/actions/runs?per_page=5", true);
        Map<String, Object> sourceScan = scanSourceArchive(ref, repository.path("default_branch").asString("main"));

        List<String> filePaths = new ArrayList<>();
        List<String> workflowPaths = new ArrayList<>();
        List<String> rootFiles = new ArrayList<>();
        Set<String> directories = new LinkedHashSet<>();
        Map<String, Integer> extensions = new HashMap<>();
        int fileCount = 0;
        int directoryCount = 0;
        boolean treeTruncated = tree == null || tree.path("truncated").asBoolean(false);
        JsonNode entries = tree == null ? null : tree.path("tree");
        if (entries != null && entries.isArray()) {
            for (JsonNode entry : entries) {
                String path = entry.path("path").asString("");
                if (path.isBlank()) continue;
                if ("tree".equals(entry.path("type").asString())) {
                    directoryCount++;
                    directories.add(path.contains("/") ? path.substring(0, path.indexOf('/')) : path);
                    continue;
                }
                if (!"blob".equals(entry.path("type").asString())) continue;
                fileCount++;
                filePaths.add(path);
                String top = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
                if (!path.contains("/")) rootFiles.add(path);
                else directories.add(top);
                String filename = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (path.toLowerCase(Locale.ROOT).startsWith(".github/workflows/")) workflowPaths.add(path);
                int dot = filename.lastIndexOf('.');
                if (dot >= 0 && dot < filename.length() - 1) extensions.merge(filename.substring(dot + 1), 1, Integer::sum);
            }
        }

        List<String> workflowNames = workflowPaths.stream().map(path -> path.substring(path.lastIndexOf('/') + 1)).toList();
        enrichBuildProfiles(sourceScan, filePaths);
        StringBuilder workflowText = new StringBuilder();
        List<String> inspectedFiles = new ArrayList<>();
        for (String path : workflowPaths.stream().limit(8).toList()) {
            JsonNode contents = request(root + "/contents/" + encodePath(path), true);
            String text = decodeContent(contents);
            if (!text.isBlank()) {
                workflowText.append('\n').append(text, 0, Math.min(text.length(), 80_000));
                inspectedFiles.add(path);
            }
        }

        Map<String, Object> readme = readme(readmeNode);
        Map<String, Object> repoInfo = repositoryInfo(repository, ref);
        List<Map<String, Object>> languageBreakdown = languageBreakdown(languages);
        List<String> techStack = detectTechStack(filePaths, languageBreakdown);
        Map<String, Object> structure = structure(rootFiles, directories, directoryCount, fileCount, extensions, treeTruncated);
        Map<String, Object> activity = activity(commitsNode, contributorsNode, releaseNode, runsNode);
        List<Map<String, Object>> issues = issues(issueNode);
        List<Map<String, Object>> pullRequests = pullRequests(pullNode);
        List<Map<String, Object>> checks = checks(workflowNames, workflowText.toString(), filePaths, sourceScan);
        List<Map<String, Object>> suggestions = suggestions(checks, readmeNode != null, repository, structure, filePaths);

        int present = (int) checks.stream().filter(check -> "PRESENT".equals(check.get("status"))).count();
        int score = checks.isEmpty() ? 0 : Math.round(present * 100f / checks.size());
        String fullName = (String) repoInfo.get("fullName");
        String language = (String) repoInfo.get("language");
        String summary = summary(fullName, language, fileCount, score, workflowNames.size(), suggestions.size(), treeTruncated);

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("schemaVersion", 3);
        analysis.put("fullName", fullName);
        analysis.put("url", repoInfo.get("url"));
        analysis.put("description", repoInfo.get("description"));
        analysis.put("language", language);
        analysis.put("defaultBranch", repoInfo.get("defaultBranch"));
        analysis.put("stars", repoInfo.get("stars"));
        analysis.put("forks", repoInfo.get("forks"));
        analysis.put("watchers", repoInfo.get("watchers"));
        analysis.put("openIssues", repoInfo.get("openIssues"));
        analysis.put("updatedAt", repoInfo.get("updatedAt"));
        analysis.put("createdAt", repoInfo.get("createdAt"));
        analysis.put("sizeKb", repoInfo.get("sizeKb"));
        analysis.put("license", repoInfo.get("license"));
        analysis.put("archived", repoInfo.get("archived"));
        analysis.put("fork", repoInfo.get("fork"));
        analysis.put("topics", repoInfo.get("topics"));
        analysis.put("readme", readme);
        analysis.put("languages", languageBreakdown);
        analysis.put("techStack", techStack);
        analysis.put("structure", structure);
        analysis.put("sourceScan", sourceScan);
        analysis.put("buildProfiles", sourceScan.getOrDefault("buildProfiles", List.of()));
        analysis.put("configurationEvidence", sourceScan.getOrDefault("configurationEvidence", List.of()));
        analysis.put("activity", activity);
        analysis.put("workflows", workflowNames);
        analysis.put("checks", checks);
        analysis.put("suggestions", suggestions);
        analysis.put("issues", issues);
        analysis.put("pullRequests", pullRequests);
        analysis.put("latestRun", activity.get("latestRun"));
        analysis.put("score", Map.of("value", score, "label", "Engineering signal coverage", "present", present, "total", checks.size(),
                "explanation", "The percentage counts detected signals in this report. It is not a code-quality or security certification."));
        analysis.put("coverage", Map.of("files", fileCount, "directories", directoryCount, "treeTruncated", treeTruncated,
                "inspectedWorkflowFiles", inspectedFiles, "readmeFound", readmeNode != null, "sourceArchiveScanned", sourceScan.get("available"),
                "note", coverageNote(treeTruncated, sourceScan)));
        analysis.put("summary", summary);
        analysis.put("analyzedAt", Instant.now().toString());
        return analysis;
    }

    public RepositoryRef parseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String[] parts = uri.getPath() == null ? new String[0] : uri.getPath().replaceAll("/$", "").split("/");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null || parts.length != 3
                    || !OWNER_OR_REPO.matcher(parts[1]).matches()) throw new IllegalArgumentException();
            String name = parts[2].replaceFirst("(?i)\\.git$", "");
            if (!OWNER_OR_REPO.matcher(name).matches() || List.of(".", "..").contains(name) || List.of(".", "..").contains(parts[1])) throw new IllegalArgumentException();
            return new RepositoryRef(parts[1], name, "https://github.com/" + parts[1] + "/" + name);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a GitHub repository URL like https://github.com/owner/repository.");
        }
    }

    private Map<String, Object> repositoryInfo(JsonNode repository, RepositoryRef ref) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fullName", repository.path("full_name").asString(ref.owner() + "/" + ref.name()));
        result.put("url", repository.path("html_url").asString(ref.canonicalUrl()));
        result.put("description", repository.path("description").asString(""));
        result.put("language", repository.path("language").isNull() ? "Not detected" : repository.path("language").asString());
        result.put("defaultBranch", repository.path("default_branch").asString("main"));
        result.put("stars", repository.path("stargazers_count").asInt());
        result.put("forks", repository.path("forks_count").asInt());
        result.put("watchers", repository.path("subscribers_count").asInt());
        result.put("openIssues", repository.path("open_issues_count").asInt());
        result.put("updatedAt", repository.path("pushed_at").asString(""));
        result.put("createdAt", repository.path("created_at").asString(""));
        result.put("sizeKb", repository.path("size").asInt());
        result.put("license", repository.path("license").path("spdx_id").asString("Not detected"));
        result.put("archived", repository.path("archived").asBoolean(false));
        result.put("fork", repository.path("fork").asBoolean(false));
        result.put("topics", textArray(repository.path("topics")));
        return result;
    }

    private Map<String, Object> readme(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("found", node != null);
        if (node == null) {
            result.put("title", ""); result.put("excerpt", ""); result.put("sections", List.of());
            return result;
        }
        String text = decodeContent(node);
        String title = text.lines().filter(line -> line.startsWith("#")).findFirst().orElse("").replaceFirst("^#+\\s*", "").trim();
        String excerpt = text.replaceAll("(?m)^```.*$", " ").replaceAll("(?m)^#+\\s*", "").replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (excerpt.length() > MAX_TEXT_CHARS) excerpt = excerpt.substring(0, MAX_TEXT_CHARS) + "…";
        List<String> sections = text.lines().filter(line -> line.matches("^#{1,3}\\s+.+")).map(line -> line.replaceFirst("^#+\\s*", "").trim()).limit(30).toList();
        result.put("title", title); result.put("excerpt", excerpt); result.put("sections", sections);
        return result;
    }

    private List<Map<String, Object>> languageBreakdown(JsonNode node) {
        Map<String, Object> values = asMap(node);
        long total = values.values().stream().filter(Number.class::isInstance).map(Number.class::cast).mapToLong(Number::longValue).sum();
        List<Map<String, Object>> result = new ArrayList<>();
        values.forEach((name, bytes) -> {
            if (bytes instanceof Number number) result.add(Map.of("name", name, "bytes", number.longValue(), "percentage", total == 0 ? 0 : Math.round(number.longValue() * 1000f / total) / 10f));
        });
        result.sort(Comparator.comparingLong(item -> -((Number) item.get("bytes")).longValue()));
        return result;
    }

    private List<String> detectTechStack(List<String> paths, List<Map<String, Object>> languages) {
        Set<String> files = new LinkedHashSet<>();
        for (String path : paths) files.add(path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
        Set<String> result = new LinkedHashSet<>();
        languages.stream().limit(4).forEach(item -> result.add((String) item.get("name")));
        addIf(files, result, "package.json", "Node.js ecosystem");
        addIf(files, result, "next.config.js", "Next.js"); addIf(files, result, "next.config.ts", "Next.js");
        addIf(files, result, "pom.xml", "Maven"); addIf(files, result, "build.gradle", "Gradle"); addIf(files, result, "build.gradle.kts", "Gradle/Kotlin DSL");
        addIf(files, result, "pyproject.toml", "Python / pyproject"); addIf(files, result, "requirements.txt", "Python / pip");
        addIf(files, result, "go.mod", "Go modules"); addIf(files, result, "cargo.toml", "Rust / Cargo");
        addIf(files, result, "dockerfile", "Docker"); addIf(files, result, "compose.yaml", "Docker Compose"); addIf(files, result, "docker-compose.yml", "Docker Compose");
        addIf(files, result, "terraform.tf", "Terraform"); addIf(files, result, "chart.yaml", "Helm");
        return result.stream().limit(12).toList();
    }

    private Map<String, Object> structure(List<String> rootFiles, Set<String> directories, int directoryCount, int fileCount,
                                          Map<String, Integer> extensions, boolean truncated) {
        List<Map<String, Object>> extensionSummary = extensions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12)
                .map(entry -> Map.<String, Object>of("extension", entry.getKey(), "count", entry.getValue())).toList();
        return Map.of("rootFiles", rootFiles.stream().limit(30).toList(), "topDirectories", directories.stream().limit(30).toList(),
                "directoryCount", directoryCount, "fileCount", fileCount, "extensions", extensionSummary, "treeTruncated", truncated);
    }

    private Map<String, Object> activity(JsonNode commits, JsonNode contributors, JsonNode release, JsonNode runs) {
        List<Map<String, Object>> recentCommits = new ArrayList<>();
        if (commits != null && commits.isArray()) for (JsonNode commit : commits) {
            JsonNode data = commit.path("commit");
            JsonNode author = commit.path("author");
            recentCommits.add(Map.of("sha", commit.path("sha").asString("").substring(0, Math.min(7, commit.path("sha").asString("").length())),
                    "message", firstLine(data.path("message").asString("")), "author", author.path("login").asString(data.path("author").path("name").asString("Unknown")),
                    "date", data.path("author").path("date").asString(""), "url", commit.path("html_url").asString("")));
            if (recentCommits.size() == 10) break;
        }
        List<Map<String, Object>> topContributors = new ArrayList<>();
        if (contributors != null && contributors.isArray()) for (JsonNode contributor : contributors) {
            topContributors.add(Map.of("login", contributor.path("login").asString("Unknown"), "contributions", contributor.path("contributions").asInt(),
                    "url", contributor.path("html_url").asString("")));
            if (topContributors.size() == 10) break;
        }
        Map<String, Object> latestRelease = release == null ? Map.of() : Map.of("name", release.path("name").asString(release.path("tag_name").asString("Release")),
                "tag", release.path("tag_name").asString(""), "publishedAt", release.path("published_at").asString(""), "url", release.path("html_url").asString(""));
        List<Map<String, Object>> runsList = new ArrayList<>();
        JsonNode workflowRuns = runs == null ? null : runs.path("workflow_runs");
        if (workflowRuns != null && workflowRuns.isArray()) for (JsonNode run : workflowRuns) {
            runsList.add(Map.of("name", run.path("name").asString("Workflow"), "status", run.path("status").asString("unknown"),
                    "conclusion", run.path("conclusion").asString(""), "branch", run.path("head_branch").asString(""),
                    "url", run.path("html_url").asString(""), "updatedAt", run.path("updated_at").asString("")));
            if (runsList.size() == 5) break;
        }
        return Map.of("recentCommits", recentCommits, "contributors", topContributors, "latestRelease", latestRelease,
                "workflowRuns", runsList, "latestRun", runsList.isEmpty() ? Map.of() : runsList.getFirst(),
                "commitCount", recentCommits.size(), "contributorCount", topContributors.size(), "lastCommitAt", recentCommits.isEmpty() ? "" : recentCommits.getFirst().get("date"));
    }

    private List<Map<String, Object>> issues(JsonNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode issue : node) {
            if (issue.has("pull_request")) continue;
            result.add(Map.of("number", issue.path("number").asInt(), "title", issue.path("title").asString("Untitled issue"),
                    "url", issue.path("html_url").asString(""), "state", issue.path("state").asString("open"), "createdAt", issue.path("created_at").asString(""),
                    "updatedAt", issue.path("updated_at").asString(""), "labels", labels(issue.path("labels"))));
            if (result.size() == 15) break;
        }
        return result;
    }

    private List<Map<String, Object>> pullRequests(JsonNode node) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode pr : node) {
            result.add(Map.of("number", pr.path("number").asInt(), "title", pr.path("title").asString("Untitled pull request"),
                    "url", pr.path("html_url").asString(""), "draft", pr.path("draft").asBoolean(false),
                    "createdAt", pr.path("created_at").asString(""), "updatedAt", pr.path("updated_at").asString(""),
                    "user", pr.path("user").path("login").asString("Unknown")));
            if (result.size() == 15) break;
        }
        return result;
    }

    private List<Map<String, Object>> checks(List<String> workflowNames, String workflowText, List<String> paths,
                                              Map<String, Object> sourceScan) {
        String workflow = workflowText.toLowerCase(Locale.ROOT);
        String allFiles = String.join(" ", paths).toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CheckDefinition check : CHECKS) {
            boolean present = switch (check.id()) {
                case "ci" -> !workflowNames.isEmpty() && check.pattern().matcher(workflow).find();
                case "tests" -> check.pattern().matcher(workflow).find() || sourceScan.get("testFiles") instanceof Number count && count.intValue() > 0
                        || allFiles.matches("(?s).*([/._-](test|tests|spec|specs)([/._-]|$)|test_[^/]+\\.py|[^/]+_test\\.go).*?");
                case "lint" -> check.pattern().matcher(workflow).find() || allFiles.contains("eslint.config") || allFiles.contains("ruff.toml") || allFiles.contains(".prettierrc") || allFiles.contains("checkstyle");
                case "container" -> allFiles.contains("dockerfile") || allFiles.contains("docker-compose") || allFiles.contains("compose.yaml") || check.pattern().matcher(workflow).find();
                case "deploy" -> check.pattern().matcher(workflow).find() || allFiles.contains("/k8s/") || allFiles.contains("/helm/") || allFiles.contains("terraform");
                case "security" -> check.pattern().matcher(workflow).find() || allFiles.contains("dependabot.yml") || allFiles.contains("dependabot.yaml");
                default -> false;
            };
            String evidence = evidence(check.id(), workflowNames, paths, present);
            if ("tests".equals(check.id()) && present && sourceScan.get("testFiles") instanceof Number count && count.intValue() > 0)
                evidence = count + " test-like source files found in the source archive.";
            result.add(Map.of("id", check.id(), "title", check.title(), "status", present ? "PRESENT" : "MISSING",
                    "detail", present ? evidence : "No matching workflow or repository files were detected.", "evidence", evidence,
                    "priority", check.priority(), "recommendation", check.suggestion()));
        }
        return result;
    }

    private Map<String, Object> scanSourceArchive(RepositoryRef ref, String branch) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", false);
        result.put("filesScanned", 0);
        result.put("sourceBytesScanned", 0);
        result.put("sourceLines", 0);
        result.put("testFiles", 0);
        result.put("todoMarkers", 0);
        result.put("fixmeMarkers", 0);
        result.put("hackMarkers", 0);
        result.put("manifestFiles", List.of());
        result.put("dependencySamples", List.of());
        result.put("buildProfiles", List.of());
        result.put("configurationEvidence", List.of());
        try {
            URI archiveUri = URI.create("https://api.github.com/repos/" + ref.owner() + "/" + ref.name()
                    + "/zipball/" + encodePathSegment(branch));
            HttpRequest.Builder request = HttpRequest.newBuilder(archiveUri).timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "TaskFlow-Pro").GET();
            if (!githubToken.isBlank()) request.header("Authorization", "Bearer " + githubToken);
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location").orElse("");
                response.body().close();
                URI redirected = URI.create(location);
                if (!"https".equalsIgnoreCase(redirected.getScheme()) || !"codeload.github.com".equalsIgnoreCase(redirected.getHost())) {
                    result.put("message", "GitHub returned an unexpected source archive location.");
                    return result;
                }
                HttpRequest archiveRequest = HttpRequest.newBuilder(redirected).timeout(Duration.ofSeconds(45))
                        .header("User-Agent", "TaskFlow-Pro").GET().build();
                response = client.send(archiveRequest, HttpResponse.BodyHandlers.ofInputStream());
                status = response.statusCode();
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                result.put("message", "GitHub did not provide a source archive (HTTP " + status + ").");
                return result;
            }
            byte[] archive;
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > MAX_ARCHIVE_BYTES) {
                        result.put("message", "The compressed source archive exceeds the 32 MB scan limit.");
                        return result;
                    }
                    output.write(buffer, 0, read);
                }
                archive = output.toByteArray();
            }

            int files = 0, entries = 0, bytes = 0, expandedBytes = 0, lines = 0, tests = 0, todo = 0, fixme = 0, hack = 0;
            boolean truncated = false;
            List<String> manifests = new ArrayList<>();
            List<String> dependencies = new ArrayList<>();
            List<Map<String, Object>> profiles = new ArrayList<>();
            List<Map<String, Object>> configurations = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(archive))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ARCHIVE_FILES) { truncated = true; break; }
                    if (entry.isDirectory()) continue;
                    String path = entry.getName();
                    String lower = path.toLowerCase(Locale.ROOT);
                    boolean inspect = !excludedSourcePath(lower) && isTextSourcePath(lower);
                    int entryLimit = inspect ? MAX_SOURCE_FILE_BYTES : 1024 * 1024;
                    int remaining = MAX_ARCHIVE_SOURCE_BYTES - expandedBytes;
                    if (remaining <= 0) { truncated = true; break; }
                    int limit = Math.min(entryLimit, remaining);
                    ByteArrayOutputStream content = inspect ? new ByteArrayOutputStream(Math.min(limit, 64 * 1024)) : null;
                    byte[] buffer = new byte[8192];
                    int consumed = 0, read;
                    boolean entryExceeded = false;
                    while ((read = zip.read(buffer)) != -1) {
                        if (consumed + read > limit) { truncated = true; entryExceeded = true; break; }
                        consumed += read;
                        if (content != null) content.write(buffer, 0, read);
                    }
                    expandedBytes += consumed;
                    if (entryExceeded) break;
                    if (content == null) continue;
                    byte[] raw = content.toByteArray();
                    if (containsBinaryByte(raw)) continue;
                    files++;
                    String text = new String(raw, StandardCharsets.UTF_8);
                    bytes += raw.length;
                    lines += text.isEmpty() ? 0 : (int) text.lines().count();
                    if (isTestPath(lower)) tests++;
                    todo += markerCount(text, "TODO");
                    fixme += markerCount(text, "FIXME");
                    hack += markerCount(text, "HACK");
                    String filename = lower.substring(lower.lastIndexOf('/') + 1);
                    String relative = path.contains("/") ? path.substring(path.indexOf('/') + 1) : path;
                    if (relative.matches("[A-Za-z0-9_./-]+") && !List.of(relative.split("/")).contains("..")) {
                        if (profiles.size() < 20) inspectBuildProfile(relative, filename, text, profiles);
                        if (configurations.size() < 40 && (filename.startsWith("dockerfile") || filename.equals("compose.yaml")
                                || filename.equals("docker-compose.yml") || relative.startsWith(".github/workflows/"))) {
                            configurations.add(Map.of("path", relative, "inspected", true,
                                    "kind", filename.startsWith("dockerfile") ? "container" : relative.startsWith(".github/workflows/") ? "workflow" : "compose",
                                    "summary", "Text inspected; presence does not establish successful execution."));
                        }
                    }
                    if (Set.of("package.json", "pom.xml", "build.gradle", "build.gradle.kts", "pyproject.toml", "requirements.txt", "go.mod", "cargo.toml", "gemfile", "composer.json").contains(filename)) {
                        manifests.add(path);
                        collectDependencySamples(filename, text, dependencies);
                    }
                }
            }
            result.put("available", true);
            result.put("filesScanned", files);
            result.put("sourceBytesScanned", bytes);
            result.put("sourceLines", lines);
            result.put("testFiles", tests);
            result.put("todoMarkers", todo);
            result.put("fixmeMarkers", fixme);
            result.put("hackMarkers", hack);
            result.put("manifestFiles", manifests.stream().limit(40).toList());
            result.put("dependencySamples", dependencies.stream().distinct().limit(30).toList());
            result.put("buildProfiles", profiles);
            result.put("configurationEvidence", configurations);
            result.put("truncated", truncated);
            result.put("message", truncated ? "Source inspection reached a safety limit; metrics cover the portion scanned." : "Source text scanned; file contents are not stored.");
            return result;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            result.put("message", "Source archive inspection was interrupted.");
        } catch (Exception exception) {
            result.put("message", "Source archive could not be inspected; GitHub metadata analysis is still available.");
        }
        result.put("truncated", false);
        return result;
    }

    private boolean excludedSourcePath(String path) {
        return path.contains("/node_modules/") || path.contains("/vendor/") || path.contains("/.git/")
                || path.contains("/dist/") || path.contains("/build/") || path.contains("/target/")
                || path.contains("/.next/") || path.contains("/coverage/") || path.contains("/__pycache__/");
    }

    private boolean isTextSourcePath(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.startsWith("dockerfile") || file.equals("makefile") || file.equals(".gitignore") || file.equals(".dockerignore")
                || file.endsWith(".java") || file.endsWith(".kt") || file.endsWith(".kts") || file.endsWith(".js")
                || file.endsWith(".jsx") || file.endsWith(".ts") || file.endsWith(".tsx") || file.endsWith(".py")
                || file.endsWith(".go") || file.endsWith(".rs") || file.endsWith(".rb") || file.endsWith(".php")
                || file.endsWith(".cs") || file.endsWith(".cpp") || file.endsWith(".c") || file.endsWith(".h")
                || file.endsWith(".swift") || file.endsWith(".sh") || file.endsWith(".yml") || file.endsWith(".yaml")
                || file.endsWith(".toml") || file.endsWith(".xml") || file.endsWith(".json") || file.endsWith(".md")
                || file.endsWith(".sql") || file.endsWith(".gradle") || file.endsWith(".properties") || file.endsWith(".txt");
    }

    private boolean isTestPath(String path) {
        return path.matches(".*([/._-](test|tests|spec|specs)([/._-]|$)|test_[^/]+\\.py|[^/]+_test\\.go).*" );
    }

    private boolean containsBinaryByte(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return true;
        return false;
    }

    private int markerCount(String text, String marker) {
        return (int) Pattern.compile("\\b" + marker + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).results().count();
    }

    private void collectDependencySamples(String filename, String text, List<String> dependencies) {
        if (filename.equals("package.json")) {
            try {
                JsonNode root = mapper.readTree(text);
                for (String section : List.of("dependencies", "devDependencies")) {
                    asMap(root.path(section)).forEach((name, version) -> {
                        if (name.matches("[@A-Za-z0-9_./-]{1,150}")) dependencies.add(name + " " +
                                (version instanceof String value && value.matches("[A-Za-z0-9.*^~<>=|+ -]{1,80}") ? value : "(custom source; inspect manifest)"));
                    });
                }
            } catch (Exception ignored) { }
        } else if (filename.equals("pom.xml")) {
            Pattern.compile("<artifactId>([A-Za-z0-9_.-]+)</artifactId>").matcher(text).results()
                    .limit(25).forEach(match -> dependencies.add("Maven: " + match.group(1)));
        } else if (filename.equals("requirements.txt")) {
            text.lines().map(String::trim).filter(line -> line.matches("[A-Za-z0-9_.-]+(?:\\[[A-Za-z0-9_,.-]+\\])?(?:[<>=!~]+[A-Za-z0-9_.*+,<>=!~-]+)?"))
                    .limit(30).forEach(dependencies::add);
        }
    }

    private void inspectBuildProfile(String path, String filename, String text, List<Map<String, Object>> profiles) {
        String directory = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("directory", directory);
        profile.put("manifest", path);
        if (filename.equals("package.json")) {
            try {
                JsonNode node = mapper.readTree(text);
                profile.put("ecosystem", "node");
                profile.put("framework", node.path("dependencies").has("next") ? "Next.js" : "Node.js");
                profile.put("scripts", List.of("build", "test", "lint", "start").stream().filter(name -> node.path("scripts").has(name)).toList());
                var nodeVersion = Pattern.compile("^[\\s^~>=v]*([0-9]{1,2})(?:\\.|$|\\s)").matcher(node.path("engines").path("node").asString(""));
                boolean versionFound = nodeVersion.find();
                profile.put("runtime", versionFound ? nodeVersion.group(1) : "22");
                profile.put("runtimeEstimated", !versionFound);
            } catch (Exception ignored) { return; }
        } else if (filename.equals("pom.xml") || filename.startsWith("build.gradle")) {
            profile.put("ecosystem", filename.equals("pom.xml") ? "maven" : "gradle");
            profile.put("framework", text.contains("spring-boot") ? "Spring Boot" : "Java");
            var version = Pattern.compile("<(?:java.version|maven.compiler.release)>([0-9]{1,2})</").matcher(text);
            profile.put("runtime", version.find() ? version.group(1) : "21");
            profile.put("runtimeEstimated", !Pattern.compile("<(?:java.version|maven.compiler.release)>[0-9]{1,2}</").matcher(text).find());
            profile.put("scripts", List.of("build", "test"));
        } else if (filename.equals("requirements.txt") || filename.equals("pyproject.toml")) {
            profile.put("ecosystem", "python");
            profile.put("framework", text.toLowerCase(Locale.ROOT).contains("django") ? "Django" : text.toLowerCase(Locale.ROOT).contains("fastapi") ? "FastAPI" : "Python");
            profile.put("runtime", "3.12");
            profile.put("runtimeEstimated", true);
            profile.put("scripts", text.toLowerCase(Locale.ROOT).contains("pytest") ? List.of("test") : List.of());
        } else return;
        profile.put("inspected", true);
        profiles.add(profile);
    }

    @SuppressWarnings("unchecked")
    private void enrichBuildProfiles(Map<String, Object> sourceScan, List<String> files) {
        for (var profile : (List<Map<String, Object>>) sourceScan.getOrDefault("buildProfiles", List.of())) {
            String directory = profile.get("directory").toString();
            String prefix = directory.equals(".") ? "" : directory + "/";
            if ("node".equals(profile.get("ecosystem"))) {
                String manager = files.contains(prefix + "pnpm-lock.yaml") ? "pnpm" : files.contains(prefix + "yarn.lock") ? "yarn" : "npm";
                profile.put("packageManager", manager);
                profile.put("lockfileDetected", files.contains(prefix + "package-lock.json") || files.contains(prefix + "pnpm-lock.yaml") || files.contains(prefix + "yarn.lock"));
            }
            if ("maven".equals(profile.get("ecosystem"))) profile.put("wrapperDetected", files.contains(prefix + "mvnw"));
            if ("gradle".equals(profile.get("ecosystem"))) profile.put("wrapperDetected", files.contains(prefix + "gradlew"));
        }
    }

    private String coverageNote(boolean treeTruncated, Map<String, Object> sourceScan) {
        String tree = treeTruncated ? "GitHub marked the recursive file tree as partial." : "The recursive Git tree was available.";
        if (!Boolean.TRUE.equals(sourceScan.get("available")))
            return tree + " Source archive inspection was unavailable; analysis is based on GitHub metadata and file paths.";
        return tree + " Text files were inspected from a bounded source archive; binaries and generated/vendor folders are skipped, and source code is not retained.";
    }

    private List<Map<String, Object>> suggestions(List<Map<String, Object>> checks, boolean hasReadme, JsonNode repository,
                                                  Map<String, Object> structure, List<String> paths) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> check : checks) if ("MISSING".equals(check.get("status"))) {
            result.add(Map.of("title", check.get("recommendation"), "description", check.get("detail"),
                    "priority", check.get("priority"), "category", "Engineering health", "source", "repository analysis"));
        }
        if (!hasReadme) result.add(suggestion("Add a README", "Document what the project does, how to run it, and how to contribute.", "MEDIUM", "Documentation"));
        if ("Not detected".equals(repository.path("license").path("spdx_id").asString("Not detected")))
            result.add(suggestion("Add a license", "Choose and add a license so users know how the project can be used.", "LOW", "Project health"));
        if (paths.stream().noneMatch(path -> path.equalsIgnoreCase(".gitignore")))
            result.add(suggestion("Add a .gitignore", "Exclude build outputs, local settings, and secrets from version control.", "LOW", "Repository hygiene"));
        if (repository.path("archived").asBoolean(false)) result.add(suggestion("Review archived project status", "This repository is archived. Confirm whether it should remain read-only or be replaced by an active project.", "LOW", "Maintenance"));
        result.sort(Comparator.comparingInt(item -> priorityRank((String) item.get("priority"))));
        return result;
    }

    private Map<String, Object> suggestion(String title, String description, String priority, String category) {
        return Map.of("title", title, "description", description, "priority", priority, "category", category, "source", "repository analysis");
    }

    private String summary(String name, String language, int files, int score, int workflows, int recommendations, boolean truncated) {
        String amount = files == 1 ? "1 file" : files + " files";
        String scope = truncated ? "GitHub returned a partial file tree" : "The repository tree contains " + amount;
        String tech = "Not detected".equals(language) ? "a mixed or unclassified codebase" : "a " + language + " codebase";
        String activity = workflows == 0 ? "No GitHub Actions workflows were found" : workflows + (workflows == 1 ? " workflow was found" : " workflows were found");
        return name + " is " + tech + ". " + scope + "; " + activity.toLowerCase(Locale.ROOT) + ". "
                + score + "% of the checked engineering signals were detected, with " + recommendations + " follow-up recommendations.";
    }

    private String evidence(String id, List<String> workflows, List<String> paths, boolean present) {
        if (!present) return "No matching workflow or repository files were detected.";
        return switch (id) {
            case "ci" -> "GitHub Actions workflow files: " + String.join(", ", workflows);
            case "tests" -> paths.stream().filter(path -> path.toLowerCase(Locale.ROOT).matches(".*([/._-](test|tests|spec|specs)([/._-]|$)|test_[^/]+\\.py|[^/]+_test\\.go).*")).limit(3).reduce((a, b) -> a + ", " + b).orElse("Test command detected in a workflow.");
            case "container" -> paths.stream().filter(path -> path.toLowerCase(Locale.ROOT).contains("dockerfile") || path.toLowerCase(Locale.ROOT).contains("compose.yaml") || path.toLowerCase(Locale.ROOT).contains("docker-compose")).limit(3).reduce((a, b) -> a + ", " + b).orElse("Container build command detected in a workflow.");
            case "deploy" -> "Deployment workflow or infrastructure configuration detected.";
            case "security" -> "Security scanner or dependency update configuration detected.";
            default -> "Lint tooling or workflow configuration detected.";
        };
    }

    private String decodeContent(JsonNode node) {
        if (node == null) return "";
        String encoded = node.path("content").asString("").replaceAll("\\s", "");
        if (encoded.isBlank() || encoded.length() > 1_500_000) return "";
        try { return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ignored) { return ""; }
    }

    private JsonNode request(String path, boolean allowNotFound) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://api.github.com/" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "TaskFlow-Pro").GET();
        if (!githubToken.isBlank()) builder.header("Authorization", "Bearer " + githubToken);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && allowNotFound) return null;
            if (response.statusCode() == 401) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub rejected the configured token. Check GITHUB_TOKEN or clear it to use public access.");
            if (response.statusCode() == 403 && "0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse("")))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub's API rate limit was reached. Configure GITHUB_TOKEN or retry after the limit resets.");
            if (response.statusCode() == 403) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GitHub denied access to this repository. It may be private or restricted for the configured token.");
            if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found or not accessible to the configured GitHub account.");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub could not return repository data (HTTP " + response.statusCode() + ").");
            return mapper.readTree(response.body());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The GitHub analysis was interrupted.");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not read GitHub repository data.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        return mapper.convertValue(node, Map.class);
    }

    private List<String> labels(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode label : node) result.add(label.path("name").asString());
        return result;
    }

    private List<String> textArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) for (JsonNode value : node) result.add(value.asString());
        return result;
    }

    private String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/", -1)).map(this::encodePathSegment).reduce((a, b) -> a + "/" + b).orElse("");
    }
    private String encodePathSegment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private String firstLine(String value) { int lineEnd = value.indexOf('\n'); return lineEnd < 0 ? value : value.substring(0, lineEnd); }
    private int priorityRank(String priority) { return switch (priority) { case "HIGH" -> 0; case "MEDIUM" -> 1; default -> 2; }; }
    private void addIf(Set<String> files, Set<String> result, String filename, String label) { if (files.contains(filename)) result.add(label); }

    public record RepositoryRef(String owner, String name, String canonicalUrl) { }
    private record CheckDefinition(String id, String title, Pattern pattern, String suggestion, String priority) { }
}
