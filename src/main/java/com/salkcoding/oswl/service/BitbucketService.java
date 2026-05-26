package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Bitbucket REST API client for creating pull requests and manipulating branches.
 *
 * Supports both Bitbucket Cloud (api.bitbucket.org/2.0) and Bitbucket Server / Data Center.
 *
 * Authentication modes:
 *   - Cloud + username   → Basic auth (Base64(username:token))
 *   - Cloud, no username → Bearer token (HTTP Access Token / ATATT…)
 *   - Server             → Bearer token
 */
@Slf4j
@Service
public class BitbucketService {

    private static final String CLOUD_API_BASE = "https://api.bitbucket.org/2.0";
    private static final String USER_AGENT      = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient   httpClient   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a Bitbucket pull request that upgrades a library version.
     *
     * @param token       Bitbucket access token or app password
     * @param username    Bitbucket username (required for app password/Basic auth; null for HTTP Access Token)
     * @param serverUrl   null/blank = Bitbucket Cloud; otherwise Bitbucket Server base URL
     * @param repoPath    "workspace/repo-slug" stored in {@code Project.githubRepo}
     * @param baseBranch  target branch for the PR
     * @param libName     library name
     * @param oldVersion  current version
     * @param newVersion  target version
     * @param prTitle     PR title
     * @param prBody      PR description
     * @param reviewers   list of reviewer account IDs or usernames (optional, best effort)
     * @return map containing "prUrl" (String) and "prNumber" (int)
     */
    public Map<String, Object> createVersionBumpPr(String token,
                                                    String username,
                                                    String serverUrl,
                                                    String repoPath,
                                                    String baseBranch,
                                                    String libName,
                                                    String oldVersion,
                                                    String newVersion,
                                                    String prTitle,
                                                    String prBody,
                                                    List<String> reviewers) {
        boolean isCloud   = (serverUrl == null || serverUrl.isBlank());
        String  authHeader = buildAuthHeader(token, username);

        if (isCloud) {
            return createCloudPr(authHeader, repoPath, baseBranch,
                    libName, oldVersion, newVersion, prTitle, prBody, reviewers);
        } else {
            return createServerPr(authHeader, serverUrl, repoPath, baseBranch,
                    libName, oldVersion, newVersion, prTitle, prBody, reviewers);
        }
    }

    /**
     * Returns the list of branch names in a Bitbucket repository.
     */
    public List<String> getBranches(String token, String username, String serverUrl, String repoPath) {
        String authHeader = buildAuthHeader(token, username);
        boolean isCloud   = (serverUrl == null || serverUrl.isBlank());
        try {
            if (isCloud) {
                String[] parts  = splitRepoPath(repoPath);
                String url = CLOUD_API_BASE + "/repositories/" + parts[0] + "/" + parts[1]
                        + "/refs/branches?pagelen=50&sort=-target.date";
                JsonNode result = getJson(authHeader, url);
                return extractCloudBranches(result);
            } else {
                return getServerBranches(authHeader, serverUrl, repoPath);
            }
        } catch (Exception e) {
            log.warn("[Bitbucket] Failed to fetch branch list for {}: {}", repoPath, e.getMessage());
            return List.of("main");
        }
    }

    // ── Bitbucket Cloud ───────────────────────────────────────────────────────

    private Map<String, Object> createCloudPr(String authHeader, String repoPath,
                                               String baseBranch, String libName,
                                               String oldVersion, String newVersion,
                                               String prTitle, String prBody,
                                               List<String> reviewers) {
        String[] parts     = splitRepoPath(repoPath);
        String workspace   = parts[0];
        String repoSlug    = parts[1];
        String apiBase     = CLOUD_API_BASE + "/repositories/" + workspace + "/" + repoSlug;

        // 1. Fetch the HEAD commit hash of the base branch
        String branchUrl = apiBase + "/refs/branches/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        JsonNode branchInfo = getJson(authHeader, branchUrl);
        String baseSha = branchInfo.path("target").path("hash").asText();

        // 2. Create the feature branch and commit the dependency file update on it
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;

        boolean manifestUpdated = updateDependencyFileCloud(
                authHeader, apiBase, newBranch, baseSha, baseBranch, libName, oldVersion, newVersion);
        if (!manifestUpdated) {
            throw new IllegalStateException(
                    "Could not find " + libName + " at version " + oldVersion
                            + " in any dependency manifest in this repository.");
        }

        // 3. Create the pull request
        var prNode = objectMapper.createObjectNode();
        prNode.put("title", prTitle);
        prNode.put("description", prBody);
        prNode.putObject("source").putObject("branch").put("name", newBranch);
        prNode.putObject("destination").putObject("branch").put("name", baseBranch);
        prNode.put("close_source_branch", true);

        if (reviewers != null && !reviewers.isEmpty()) {
            var reviewersArray = prNode.putArray("reviewers");
            reviewers.forEach(r -> reviewersArray.addObject().put("account_id", r));
        }

        JsonNode pr    = postJson(authHeader, apiBase + "/pullrequests", prNode.toString());
        int    prId    = pr.path("id").asInt();
        String prUrl   = pr.path("links").path("html").path("href").asText();

        log.info("[Bitbucket Cloud] PR #{} created: {}", prId, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prId);
    }

    private boolean updateDependencyFileCloud(String authHeader, String apiBase,
                                               String newBranch, String baseSha, String baseBranch,
                                               String libName, String oldVersion, String newVersion) {
        List<String> paths = discoverCloudManifestPaths(authHeader, apiBase, baseBranch);
        if (paths.isEmpty()) {
            paths = List.of(
                    "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                    "requirements.txt", "pyproject.toml", "go.mod", "Cargo.toml"
            );
        }

        for (String path : paths) {
            try {
                String fileUrl = apiBase + "/src/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8)
                        + "/" + path;
                String content = getRawText(authHeader, fileUrl);
                var updated = DependencyManifestPatcher.patch(content, path, libName, oldVersion, newVersion);
                if (updated.isEmpty()) continue;

                String message = "chore: bump " + libName + " from " + oldVersion
                        + " to " + newVersion + " [OsWL]";
                commitFileCloud(authHeader, apiBase + "/src", path, updated.get(), newBranch, baseSha, message);
                log.info("[Bitbucket Cloud] Updated {} on branch {}", path, newBranch);
                return true;
            } catch (Exception e) {
                log.debug("[Bitbucket Cloud] Skipping {}: {}", path, e.getMessage());
            }
        }
        log.warn("[Bitbucket Cloud] Dependency file not updated — {} {} not found in manifests", libName, oldVersion);
        return false;
    }

    /** Lists manifest paths via Bitbucket file search (best-effort). */
    private List<String> discoverCloudManifestPaths(String authHeader, String apiBase, String baseBranch) {
        List<String> found = new ArrayList<>();
        for (String fileName : List.of("package.json", "pom.xml", "build.gradle", "build.gradle.kts")) {
            try {
                String url = apiBase + "/src/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8)
                        + "/?search=" + URLEncoder.encode(fileName, StandardCharsets.UTF_8);
                JsonNode root = getJson(authHeader, url);
                JsonNode values = root.path("values");
                if (values.isArray()) {
                    values.forEach(v -> {
                        String path = v.path("path").asText();
                        if (DependencyManifestPatcher.isManifestPath(path)) found.add(path);
                    });
                }
            } catch (Exception e) {
                log.debug("[Bitbucket Cloud] Search for {} failed: {}", fileName, e.getMessage());
            }
        }
        found.sort(java.util.Comparator
                .comparingInt((String p) -> (int) p.chars().filter(ch -> ch == '/').count())
                .thenComparing(p -> p));
        return found.stream().distinct().toList();
    }

    /**
     * Commits a single file through the Bitbucket Cloud multipart src endpoint.
     * If the branch does not exist, it is created from the given parent commit.
     */
    private void commitFileCloud(String authHeader, String srcUrl,
                                  String filePath, String content,
                                  String branch, String parentSha, String message) throws Exception {
        // Build multipart/form-data manually
        String boundary = "----OsWLBoundary" + System.currentTimeMillis();
        var sb = new StringBuilder();

        appendFormField(sb, boundary, "branch", branch);
        appendFormField(sb, boundary, "parents", parentSha);
        appendFormField(sb, boundary, "message", message);
        appendFormField(sb, boundary, filePath, content);
        sb.append("--").append(boundary).append("--\r\n");

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(srcUrl))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
        if (response.statusCode() >= 400) {
            log.warn("[Bitbucket Cloud] POST {} → HTTP {} {}", srcUrl, response.statusCode(), response.body());
            throw new RuntimeException("Bitbucket API error: " + response.statusCode());
        }
    }

    private List<String> extractCloudBranches(JsonNode result) {
        List<String> branches = new ArrayList<>();
        JsonNode values = result.path("values");
        if (values.isArray()) {
            values.forEach(b -> branches.add(b.path("name").asText()));
        }
        return branches.isEmpty() ? List.of("main") : branches;
    }

    // ── Bitbucket Server / Data Center ────────────────────────────────────────

    private Map<String, Object> createServerPr(String authHeader, String serverUrl,
                                                String repoPath, String baseBranch,
                                                String libName, String oldVersion, String newVersion,
                                                String prTitle, String prBody,
                                                List<String> reviewers) {
        // Server path: {serverUrl}/rest/api/1.0/projects/{PROJECT}/repos/{repo}
        // The project key and repo slug are stored in githubRepo as "PROJECT/repo-slug"
        String[] parts      = splitRepoPath(repoPath);
        String projectKey   = parts[0].toUpperCase();
        String repoSlug     = parts[1];
        String apiBase      = serverUrl.replaceAll("/+$", "")
                + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repoSlug;

        // 1. Fetch the HEAD commit of the base branch
        String branchUrl = apiBase + "/branches?filterText=" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        JsonNode branchList = getJson(authHeader, branchUrl);
        String baseSha = "";
        JsonNode values = branchList.path("values");
        if (values.isArray()) {
            for (JsonNode b : values) {
                if (baseBranch.equals(b.path("displayId").asText())) {
                    baseSha = b.path("latestCommit").asText();
                    break;
                }
            }
        }

        // 2. Create the feature branch
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createServerBranch(authHeader, apiBase, newBranch, baseSha);

        // 3. Find and update the dependency file
        boolean manifestUpdated = updateDependencyFileServer(
                authHeader, apiBase, newBranch, baseBranch, libName, oldVersion, newVersion);
        if (!manifestUpdated) {
            throw new IllegalStateException(
                    "Could not find " + libName + " at version " + oldVersion
                            + " in any dependency manifest in this repository.");
        }

        // 4. Create the pull request
        var prPayload = objectMapper.createObjectNode();
        prPayload.put("title", prTitle);
        prPayload.put("description", prBody);
        prPayload.putObject("fromRef").put("id", "refs/heads/" + newBranch);
        prPayload.putObject("toRef").put("id", "refs/heads/" + baseBranch);

        JsonNode pr   = postJson(authHeader, apiBase + "/pull-requests", prPayload.toString());
        int    prId   = pr.path("id").asInt();
        String prUrl  = pr.path("links").path("self").get(0).path("href").asText();

        log.info("[Bitbucket Server] PR #{} created: {}", prId, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prId);
    }

    private void createServerBranch(String authHeader, String apiBase, String branch, String startPoint) {
        String url = apiBase.replaceFirst("/projects/.*", "") + "/rest/api/1.0/projects/"
                + extractProjectKey(apiBase) + "/repos/"
                + extractRepoSlug(apiBase) + "/branches";
        String payload = objectMapper.createObjectNode()
                .put("name", branch)
                .put("startPoint", startPoint)
                .toString();
        postJson(authHeader, url, payload);
    }

    private boolean updateDependencyFileServer(String authHeader, String apiBase,
                                                String branch, String baseBranch,
                                                String libName, String oldVersion, String newVersion) {
        List<String> paths = List.of(
                "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                "requirements.txt", "pyproject.toml", "go.mod", "Cargo.toml"
        );

        for (String path : paths) {
            try {
                String fileUrl = apiBase + "/raw/" + path + "?at=refs/heads/" + baseBranch;
                String content = getRawText(authHeader, fileUrl);
                var updated = DependencyManifestPatcher.patch(content, path, libName, oldVersion, newVersion);
                if (updated.isEmpty()) continue;

                commitFileServer(authHeader, apiBase, path, updated.get(), branch,
                        "chore: bump " + libName + " from " + oldVersion + " to " + newVersion + " [OsWL]");
                log.info("[Bitbucket Server] Updated {} on branch {}", path, branch);
                return true;
            } catch (Exception e) {
                log.debug("[Bitbucket Server] Skipping {}: {}", path, e.getMessage());
            }
        }
        log.warn("[Bitbucket Server] Dependency file not updated — {} {} not found", libName, oldVersion);
        return false;
    }

    private void commitFileServer(String authHeader, String apiBase,
                                   String filePath, String content,
                                   String branch, String message) throws Exception {
        String url     = apiBase + "/browse/" + filePath;
        String boundary = "----OsWLBoundary" + System.currentTimeMillis();
        var sb = new StringBuilder();
        appendFormField(sb, boundary, "branch", branch);
        appendFormField(sb, boundary, "message", message);
        appendFormField(sb, boundary, "content", content);
        sb.append("--").append(boundary).append("--\r\n");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .PUT(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Bitbucket Server API error: " + response.statusCode());
        }
    }

    private List<String> getServerBranches(String authHeader, String serverUrl, String repoPath) {
        String[] parts    = splitRepoPath(repoPath);
        String projectKey = parts[0].toUpperCase();
        String repoSlug   = parts[1];
        String url = serverUrl.replaceAll("/+$", "")
                + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repoSlug
                + "/branches?orderBy=MODIFICATION&limit=50";
        JsonNode result = getJson(authHeader, url);
        List<String> branches = new ArrayList<>();
        JsonNode values = result.path("values");
        if (values.isArray()) {
            values.forEach(b -> branches.add(b.path("displayId").asText()));
        }
        return branches.isEmpty() ? List.of("main") : branches;
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private String buildAuthHeader(String token, String username) {
        if (username != null && !username.isBlank()) {
            String credentials = username + ":" + token;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + token;
    }

    private String[] splitRepoPath(String repoPath) {
        int slash = repoPath.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Invalid Bitbucket repository path: " + repoPath);
        return new String[]{ repoPath.substring(0, slash), repoPath.substring(slash + 1) };
    }

    private String extractProjectKey(String apiBase) {
        // Pattern: .../projects/{KEY}/repos/...
        int pi = apiBase.indexOf("/projects/");
        int ri = apiBase.indexOf("/repos/", pi);
        return apiBase.substring(pi + 10, ri);
    }

    private String extractRepoSlug(String apiBase) {
        int ri = apiBase.indexOf("/repos/");
        return apiBase.substring(ri + 7);
    }

    private void appendFormField(StringBuilder sb, String boundary, String name, String value) {
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n");
        sb.append(value).append("\r\n");
    }

    private JsonNode getJson(String authHeader, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[Bitbucket] GET {} → HTTP {}", url, response.statusCode());
                throw new RuntimeException("Bitbucket API error: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Bitbucket] GET failed: {}", url, e);
            throw new RuntimeException("Bitbucket API call failed: " + e.getMessage());
        }
    }

    private String getRawText(String authHeader, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
            if (response.statusCode() == 404) throw new RuntimeException("File not found: " + url);
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Bitbucket API error: " + response.statusCode());
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch raw file from Bitbucket: " + e.getMessage());
        }
    }

    private JsonNode postJson(String authHeader, String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[Bitbucket] POST {} → HTTP {} {}", url, response.statusCode(), response.body());
                throw new RuntimeException("Bitbucket API error: " + response.statusCode() + " — " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Bitbucket] POST failed: {}", url, e);
            throw new RuntimeException("Bitbucket API call failed: " + e.getMessage());
        }
    }
}
