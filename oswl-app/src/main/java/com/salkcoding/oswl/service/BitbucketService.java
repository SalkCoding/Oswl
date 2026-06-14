package com.salkcoding.oswl.service;

import com.salkcoding.oswl.client.BitbucketCloudAuth;
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
 * Supports both Bitbucket Cloud (api.bitbucket.org/2.0) and Bitbucket Server / Data Center.
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
        boolean isCloud   = isBitbucketCloud(serverUrl);
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
        boolean isCloud   = isBitbucketCloud(serverUrl);
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

        log.debug("[Bitbucket Cloud] Version bump PR {}/{} {}:{} → {} baseBranch={}",
                workspace, repoSlug, libName, oldVersion, newVersion, baseBranch);

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
        String[] parts    = splitRepoPath(repoPath);
        String projectKey = parts[0].toUpperCase();
        String repoSlug   = parts[1];
        String apiBase    = serverApiBase(serverUrl, projectKey, repoSlug);

        log.debug("[Bitbucket Server] Version bump PR {}/{} {}:{} → {} baseBranch={}",
                projectKey, repoSlug, libName, oldVersion, newVersion, baseBranch);

        // 1. Fetch the HEAD commit of the base branch
        String baseSha = resolveServerBranchHead(authHeader, apiBase, baseBranch);
        if (baseSha.isBlank()) {
            throw new IllegalStateException("Base branch not found: " + baseBranch);
        }

        // 2. Create the feature branch
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createServerBranch(authHeader, apiBase, newBranch, baseSha);

        // 3. Find and update the dependency file on the new branch
        boolean manifestUpdated = updateDependencyFileServer(
                authHeader, apiBase, newBranch, baseBranch, baseSha, libName, oldVersion, newVersion);
        if (!manifestUpdated) {
            throw new IllegalStateException(
                    "Could not find " + libName + " at version " + oldVersion
                            + " in any dependency manifest in this repository.");
        }

        // 4. Create the pull request (Server REST API requires full ref + repository objects)
        String payload = buildServerPrPayload(projectKey, repoSlug, newBranch, baseBranch, prTitle, prBody).toString();
        JsonNode pr  = postJson(authHeader, apiBase + "/pull-requests", payload);
        int    prId  = pr.path("id").asInt();
        String prUrl = extractServerPrUrl(pr, serverUrl, projectKey, repoSlug, prId);

        log.info("[Bitbucket Server] PR #{} created: {}", prId, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prId);
    }

    private String serverApiBase(String serverUrl, String projectKey, String repoSlug) {
        return normalizeServerUrl(serverUrl)
                + "/rest/api/1.0/projects/" + projectKey + "/repos/" + repoSlug;
    }

    private String normalizeServerUrl(String serverUrl) {
        return serverUrl.replaceAll("/+$", "");
    }

    private String resolveServerBranchHead(String authHeader, String apiBase, String branchName) {
        String url = apiBase + "/branches?filterText="
                + URLEncoder.encode(branchName, StandardCharsets.UTF_8) + "&limit=50";
        JsonNode branchList = getJson(authHeader, url);
        JsonNode values = branchList.path("values");
        if (values.isArray()) {
            for (JsonNode b : values) {
                if (branchName.equals(b.path("displayId").asText())
                        || branchName.equals(b.path("id").asText("").replace("refs/heads/", ""))) {
                    return b.path("latestCommit").asText("");
                }
            }
        }
        return "";
    }

    private com.fasterxml.jackson.databind.node.ObjectNode buildServerPrPayload(
            String projectKey, String repoSlug, String fromBranch, String toBranch,
            String prTitle, String prBody) {
        var prPayload = objectMapper.createObjectNode();
        prPayload.put("title", prTitle);
        prPayload.put("description", prBody);
        prPayload.put("state", "OPEN");
        prPayload.put("open", true);
        prPayload.put("closed", false);

        var fromRef = prPayload.putObject("fromRef");
        fromRef.put("id", "refs/heads/" + fromBranch);
        var fromRepo = fromRef.putObject("repository");
        fromRepo.put("slug", repoSlug);
        fromRepo.putObject("project").put("key", projectKey);

        var toRef = prPayload.putObject("toRef");
        toRef.put("id", "refs/heads/" + toBranch);
        var toRepo = toRef.putObject("repository");
        toRepo.put("slug", repoSlug);
        toRepo.putObject("project").put("key", projectKey);
        return prPayload;
    }

    private String extractServerPrUrl(JsonNode pr, String serverUrl, String projectKey, String repoSlug, int prId) {
        JsonNode self = pr.path("links").path("self");
        if (self.isArray() && !self.isEmpty()) {
            return self.get(0).path("href").asText();
        }
        if (self.isObject() && self.has("href")) {
            return self.path("href").asText();
        }
        return normalizeServerUrl(serverUrl)
                + "/projects/" + projectKey + "/repos/" + repoSlug + "/pull-requests/" + prId;
    }

    private void createServerBranch(String authHeader, String apiBase, String branch, String startPoint) {
        String payload = objectMapper.createObjectNode()
                .put("name", branch)
                .put("startPoint", startPoint)
                .toString();
        postJson(authHeader, apiBase + "/branches", payload);
    }

    private boolean updateDependencyFileServer(String authHeader, String apiBase,
                                                String branch, String baseBranch, String baseSha,
                                                String libName, String oldVersion, String newVersion) {
        List<String> paths = List.of(
                "package.json", "pom.xml", "build.gradle", "build.gradle.kts",
                "requirements.txt", "pyproject.toml", "go.mod", "Cargo.toml"
        );

        for (String path : paths) {
            try {
                String fileUrl = apiBase + "/raw/" + encodePath(path)
                        + "?at=" + URLEncoder.encode("refs/heads/" + baseBranch, StandardCharsets.UTF_8);
                String content = getRawText(authHeader, fileUrl);
                var updated = DependencyManifestPatcher.patch(content, path, libName, oldVersion, newVersion);
                if (updated.isEmpty()) continue;

                commitFileServer(authHeader, apiBase, path, updated.get(), branch, baseSha,
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

    private String encodePath(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void commitFileServer(String authHeader, String apiBase,
                                   String filePath, String content,
                                   String branch, String sourceCommitId, String message) throws Exception {
        String url = apiBase + "/browse/" + encodePath(filePath);
        String formBody = "branch=" + URLEncoder.encode(branch, StandardCharsets.UTF_8)
                + "&content=" + URLEncoder.encode(content, StandardCharsets.UTF_8)
                + "&message=" + URLEncoder.encode(message, StandardCharsets.UTF_8)
                + "&sourceCommitId=" + URLEncoder.encode(sourceCommitId, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .PUT(HttpRequest.BodyPublishers.ofString(formBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401) throw new RuntimeException("Bitbucket token is invalid or expired");
        if (response.statusCode() >= 400) {
            log.warn("[Bitbucket Server] PUT {} → HTTP {} {}", url, response.statusCode(), response.body());
            throw new RuntimeException("Bitbucket Server API error: " + response.statusCode());
        }
    }

    private List<String> getServerBranches(String authHeader, String serverUrl, String repoPath) {
        String[] parts    = splitRepoPath(repoPath);
        String projectKey = parts[0].toUpperCase();
        String repoSlug   = parts[1];
        String url = serverApiBase(serverUrl, projectKey, repoSlug)
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
            BitbucketCloudAuth auth = BitbucketCloudAuth.parse(username, token);
            if (auth.mode() == BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT) {
                return auth.authHeader(token);
            }
            if (token != null && token.startsWith("ATATT")) {
                return "Bearer " + token;
            }
            String credentials = username + ":" + token;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        }
        return "Bearer " + token;
    }

    private static boolean isBitbucketCloud(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) return true;
        String normalized = serverUrl.trim().replaceAll("/+$", "").toLowerCase();
        return normalized.equals("https://bitbucket.org") || normalized.equals("http://bitbucket.org");
    }

    private String[] splitRepoPath(String repoPath) {
        int slash = repoPath.indexOf('/');
        if (slash < 0) throw new IllegalArgumentException("Invalid Bitbucket repository path: " + repoPath);
        return new String[]{ repoPath.substring(0, slash), repoPath.substring(slash + 1) };
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
