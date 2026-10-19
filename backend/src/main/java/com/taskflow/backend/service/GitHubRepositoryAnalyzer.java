package com.taskflow.backend.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class GitHubRepositoryAnalyzer {
    private static final Pattern OWNER_OR_REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final List<CheckDefinition> CHECKS = List.of(
            new CheckDefinition("ci", "CI workflow", Pattern.compile("\\b(on:|push:|pull_request:|workflow_dispatch:)"), "Add a GitHub Actions workflow that runs on pushes and pull requests.", "HIGH"),
            new CheckDefinition("tests", "Automated tests", Pattern.compile("\\b(test|pytest|mvn test|gradle test|go test|jest|vitest)\\b"), "Run automated tests on every pull request.", "HIGH"),
            new CheckDefinition("lint", "Linting", Pattern.compile("\\b(lint|eslint|ruff|flake8|checkstyle)\\b"), "Add a lint step so style and common code issues are caught in CI.", "MEDIUM"),
            new CheckDefinition("container", "Container build", Pattern.compile("docker/build-push-action|docker build|buildah|build-image"), "Build the Docker image in CI to catch container build failures early.", "MEDIUM"),
            new CheckDefinition("deploy", "Deployment workflow", Pattern.compile("\\b(deploy|kubectl|helm|aws-actions|azure/webapps-deploy)\\b"), "Add a deployment job for a staging environment after CI passes.", "MEDIUM"),
            new CheckDefinition("security", "Security scanning", Pattern.compile("trivy|snyk|codeql|dependency-review|gitleaks|trufflehog"), "Add dependency, secret, and container vulnerability scanning to the pipeline.", "HIGH")
    );

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GitHubRepositoryAnalyzer(ObjectMapper mapper) { this.mapper = mapper; }

    public Map<String, Object> analyze(String repositoryUrl) {
        RepositoryRef ref = parseUrl(repositoryUrl);
        JsonNode repository = request("repos/" + ref.owner() + "/" + ref.name(), false);
        String fullName = repository.path("full_name").asText(ref.owner() + "/" + ref.name());

        StringBuilder workflowText = new StringBuilder();
        List<String> workflowNames = new ArrayList<>();
        JsonNode workflowFiles = request("repos/" + ref.owner() + "/" + ref.name() + "/contents/.github/workflows", true);
        if (workflowFiles != null && workflowFiles.isArray()) {
            for (JsonNode file : workflowFiles) {
                if (!"file".equals(file.path("type").asText())) continue;
                String path = file.path("path").asText();
                workflowNames.add(file.path("name").asText(path));
                if (workflowNames.size() > 8) continue;
                String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
                JsonNode content = request("repos/" + ref.owner() + "/" + ref.name() + "/contents/" + encodedPath, true);
                String base64 = content == null ? "" : content.path("content").asText("").replaceAll("\\s", "");
                if (!base64.isEmpty() && base64.length() <= 700_000) {
                    try { workflowText.append(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8)).append('\n'); }
                    catch (IllegalArgumentException ignored) { workflowText.append(' '); }
                }
            }
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> suggestions = new ArrayList<>();
        String workflow = (String.join(" ", workflowNames) + "\n" + workflowText).toLowerCase();
        for (CheckDefinition check : CHECKS) {
            boolean found = !workflowNames.isEmpty() && check.pattern().matcher(workflow).find();
            String state = found ? "PRESENT" : "MISSING";
            checks.add(Map.of("id", check.id(), "title", check.title(), "status", state,
                    "detail", found ? "Detected in the repository workflows." : "Not detected in the repository workflows."));
            if ("MISSING".equals(state)) suggestions.add(Map.of("title", check.title(), "description", check.suggestion(), "priority", check.priority(), "category", "DevOps"));
        }

        List<Map<String, Object>> issues = new ArrayList<>();
        JsonNode issueData = request("repos/" + ref.owner() + "/" + ref.name() + "/issues?state=open&per_page=20", true);
        if (issueData != null && issueData.isArray()) {
            for (JsonNode issue : issueData) {
                if (issue.has("pull_request") || issues.size() >= 8) continue;
                issues.add(Map.of("number", issue.path("number").asInt(), "title", issue.path("title").asText("Untitled issue"),
                        "url", issue.path("html_url").asText(""), "labels", labels(issue.path("labels"))));
            }
        }

        Map<String, Object> analysis = new LinkedHashMap<>();
        analysis.put("fullName", fullName);
        analysis.put("url", repository.path("html_url").asText(ref.canonicalUrl()));
        analysis.put("description", repository.path("description").asText(""));
        analysis.put("language", repository.path("language").isNull() ? "Not detected" : repository.path("language").asText());
        analysis.put("defaultBranch", repository.path("default_branch").asText("main"));
        analysis.put("stars", repository.path("stargazers_count").asInt());
        analysis.put("forks", repository.path("forks_count").asInt());
        analysis.put("openIssues", repository.path("open_issues_count").asInt());
        analysis.put("updatedAt", repository.path("pushed_at").asText(""));
        analysis.put("topics", textArray(repository.path("topics")));
        analysis.put("workflows", workflowNames);
        analysis.put("checks", checks);
        analysis.put("suggestions", suggestions);
        analysis.put("issues", issues);
        analysis.put("latestRun", latestRun(ref));
        analysis.put("summary", workflowNames.isEmpty()
                ? "No GitHub Actions workflows were found. The backlog below highlights a practical CI/CD starting point."
                : "Repository workflows were inspected for tests, linting, image builds, deployment, and security scanning.");
        return analysis;
    }

    public RepositoryRef parseUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String[] parts = uri.getPath() == null ? new String[0] : uri.getPath().replaceAll("/$", "").split("/");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null || parts.length != 3
                    || !OWNER_OR_REPO.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException();
            }
            String name = parts[2].replaceFirst("(?i)\\.git$", "");
            if (!OWNER_OR_REPO.matcher(name).matches()) throw new IllegalArgumentException();
            String canonicalUrl = "https://github.com/" + parts[1] + "/" + name;
            return new RepositoryRef(parts[1], name, canonicalUrl);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a GitHub repository URL like https://github.com/owner/repository.");
        }
    }

    private JsonNode latestRun(RepositoryRef ref) {
        JsonNode response = request("repos/" + ref.owner() + "/" + ref.name() + "/actions/runs?per_page=1", true);
        JsonNode runs = response == null ? null : response.path("workflow_runs");
        if (runs == null || !runs.isArray() || runs.isEmpty()) return null;
        JsonNode run = runs.get(0);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", run.path("name").asText("Workflow"));
        result.put("status", run.path("status").asText("unknown"));
        result.put("conclusion", run.path("conclusion").isNull() ? "" : run.path("conclusion").asText());
        result.put("branch", run.path("head_branch").asText(""));
        result.put("url", run.path("html_url").asText(""));
        result.put("updatedAt", run.path("updated_at").asText(""));
        return mapper.valueToTree(result);
    }

    private JsonNode request(String path, boolean allowNotFound) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/" + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TaskFlow-Pro")
                .GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 && allowNotFound) return null;
            if (response.statusCode() == 403 && "0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse("")))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "GitHub's public API rate limit was reached. Try again later.");
            if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Repository not found or not publicly accessible.");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub could not return repository data (HTTP " + response.statusCode() + ").");
            return mapper.readTree(response.body());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The GitHub analysis was interrupted.");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not reach GitHub to analyze this repository.");
        }
    }

    private List<String> labels(JsonNode labels) {
        List<String> result = new ArrayList<>();
        if (labels != null && labels.isArray()) for (JsonNode label : labels) result.add(label.path("name").asText());
        return result;
    }

    private List<String> textArray(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values != null && values.isArray()) for (JsonNode value : values) result.add(value.asText());
        return result;
    }

    public record RepositoryRef(String owner, String name, String canonicalUrl) { }
    private record CheckDefinition(String id, String title, Pattern pattern, String suggestion, String priority) { }
}
