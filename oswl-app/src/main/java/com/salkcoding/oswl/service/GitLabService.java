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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GitLab REST API client for Merge Request creation and branch operations.
 * Supports both gitlab.com (cloud) and self-hosted GitLab instances.
 *
 * Auth: PRIVATE-TOKEN header (Personal Access Token or project token).
 */
@Slf4j
@Service
public class GitLabService {

    private static final String DEFAULT_BASE = "https://gitlab.com";
    private static final String USER_AGENT   = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient   httpClient   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a GitLab Merge Request that bumps the library version.
     *
     * @param token       GitLab personal access token
     * @param serverUrl   null/blank = cloud (gitlab.com), otherwise on-prem base URL
     * @param projectPath "namespace/project" stored in {@code Project.githubRepo}
     * @param baseBranch  MR target branch
     * @param libName     library name
     * @param oldVersion  current version
     * @param newVersion  target version
     * @param mrTitle     MR title
     * @param mrBody      MR description
     * @param reviewers   optional list of reviewer usernames (best-effort)
     * @return map with "prUrl" (String) and "prNumber" (int iid)
     */
    public Map<String, Object> createVersionBumpMr(String token,
                                                    String serverUrl,
                                                    String projectPath,
                                                    String baseBranch,
                                                    String libName,
                                                    String oldVersion,
                                                    String newVersion,
                                                    String mrTitle,
                                                    String mrBody,
                                                    List<String> reviewers) {
        String base        = buildBase(serverUrl);
        String encodedPath = encodeProjectPath(projectPath);
        String resolvedBase = resolveBaseBranch(token, base, encodedPath, baseBranch);

        // 1. Get base branch HEAD SHA
        String branchUrl = base + "/api/v4/projects/" + encodedPath
                + "/repository/branches/" + URLEncoder.encode(resolvedBase, StandardCharsets.UTF_8);
        JsonNode branchInfo = getJson(token, branchUrl);
        String baseSha = branchInfo.path("commit").path("id").asText();

        // 2. Create feature branch
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createBranch(token, base, encodedPath, newBranch, baseSha);

        // 3. Find and update the dependency file
        boolean manifestUpdated = updateDependencyFile(
                token, base, encodedPath, newBranch, resolvedBase, libName, oldVersion, newVersion);
        if (!manifestUpdated) {
            deleteBranchQuietly(token, base, encodedPath, newBranch);
            throw new IllegalStateException(
                    "Could not find " + libName + " at version " + oldVersion
                            + " in any dependency manifest in this repository. "
                            + "OsWL searches common manifest files including subdirectories.");
        }

        // 4. Create merge request
        String mrPayload = objectMapper.createObjectNode()
                .put("source_branch", newBranch)
                .put("target_branch", resolvedBase)
                .put("title", mrTitle)
                .put("description", mrBody)
                .put("remove_source_branch", true)
                .toString();

        JsonNode mr     = postJson(token, base + "/api/v4/projects/" + encodedPath + "/merge_requests", mrPayload);
        int    mrIid    = mr.path("iid").asInt();
        String mrUrl    = mr.path("web_url").asText();

        // 5. Optionally assign reviewers (best-effort; requires GitLab Premium for some endpoints)
        if (reviewers != null && !reviewers.isEmpty()) {
            try {
                assignReviewers(token, base, encodedPath, mrIid, reviewers);
            } catch (Exception e) {
                log.warn("[GitLab] Failed to assign reviewers to MR !{}: {}", mrIid, e.getMessage());
            }
        }

        log.info("[GitLab] MR !{} created: {}", mrIid, mrUrl);
        return Map.of("prUrl", mrUrl, "prNumber", mrIid);
    }

    /**
     * Returns the branch names for a GitLab project (sorted by last update, descending).
     */
    public List<String> getBranches(String token, String serverUrl, String projectPath) {
        String base        = buildBase(serverUrl);
        String encodedPath = encodeProjectPath(projectPath);
        // GitLab branches API only supports per_page/search — no order_by (returns 400 if used).
        String url = base + "/api/v4/projects/" + encodedPath + "/repository/branches?per_page=100";
        try {
            JsonNode arr = getJson(token, url);
            if (!arr.isArray() || arr.isEmpty()) {
                return fallbackBranches(token, base, encodedPath);
            }
            List<BranchEntry> entries = new ArrayList<>();
            arr.forEach(b -> entries.add(new BranchEntry(
                    b.path("name").asText(),
                    b.path("default").asBoolean(false),
                    b.path("commit").path("committed_date").asText(""))));
            entries.sort(Comparator
                    .comparing((BranchEntry e) -> !e.isDefault)
                    .thenComparing(BranchEntry::committedDate, Comparator.reverseOrder())
                    .thenComparing(BranchEntry::name));
            return entries.stream().map(BranchEntry::name).toList();
        } catch (Exception e) {
            log.warn("[GitLab] Could not list branches for {}: {}", projectPath, e.getMessage());
            return fallbackBranches(token, base, encodedPath);
        }
    }

    /** Returns the project's default_branch from the GitLab project API. */
    public String getDefaultBranch(String token, String serverUrl, String projectPath) {
        String base        = buildBase(serverUrl);
        String encodedPath = encodeProjectPath(projectPath);
        return fetchProjectDefaultBranch(token, base, encodedPath);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void createBranch(String token, String base, String encodedPath,
                               String branch, String ref) {
        if (branchExists(token, base, encodedPath, branch)) {
            log.info("[GitLab] Branch {} already exists — deleting before recreate", branch);
            deleteBranchQuietly(token, base, encodedPath, branch);
        }
        String url = base + "/api/v4/projects/" + encodedPath + "/repository/branches";
        String payload = objectMapper.createObjectNode()
                .put("branch", branch)
                .put("ref", ref)
                .toString();
        postJson(token, url, payload);
    }

    private String resolveBaseBranch(String token, String base, String encodedPath, String requested) {
        if (requested != null && !requested.isBlank() && branchExists(token, base, encodedPath, requested)) {
            return requested;
        }
        String defaultBranch = fetchProjectDefaultBranch(token, base, encodedPath);
        if (defaultBranch != null && branchExists(token, base, encodedPath, defaultBranch)) {
            if (requested != null && !requested.isBlank() && !requested.equals(defaultBranch)) {
                log.info("[GitLab] Base branch '{}' not found — using default '{}'", requested, defaultBranch);
            }
            return defaultBranch;
        }
        for (String candidate : List.of("main", "master")) {
            if (branchExists(token, base, encodedPath, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not find base branch '" + requested + "' or a default branch in this GitLab project.");
    }

    private boolean branchExists(String token, String base, String encodedPath, String branch) {
        try {
            String url = base + "/api/v4/projects/" + encodedPath
                    + "/repository/branches/" + URLEncoder.encode(branch, StandardCharsets.UTF_8);
            getJson(token, url);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String fetchProjectDefaultBranch(String token, String base, String encodedPath) {
        try {
            JsonNode project = getJson(token, base + "/api/v4/projects/" + encodedPath);
            String def = project.path("default_branch").asText("");
            return def.isBlank() ? null : def;
        } catch (Exception e) {
            log.debug("[GitLab] Could not read default_branch: {}", e.getMessage());
            return null;
        }
    }

    private List<String> fallbackBranches(String token, String base, String encodedPath) {
        String def = fetchProjectDefaultBranch(token, base, encodedPath);
        if (def != null) {
            return List.of(def);
        }
        return List.of("main", "master");
    }

    private static String encodeProjectPath(String projectPath) {
        return URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
    }

    private record BranchEntry(String name, boolean isDefault, String committedDate) {}

    private boolean updateDependencyFile(String token, String base, String encodedPath,
                                          String branch, String baseBranch,
                                          String libName, String oldVersion, String newVersion) {
        List<String> paths = discoverManifestPaths(token, base, encodedPath, baseBranch);
        if (paths.isEmpty()) {
            paths = List.of(
                    "package.json", "package-lock.json", "pom.xml", "build.gradle", "build.gradle.kts",
                    "requirements.txt", "pyproject.toml", "go.mod", "Cargo.toml"
            );
        }

        for (String path : paths) {
            try {
                String encodedFile = URLEncoder.encode(path, StandardCharsets.UTF_8);
                String fileUrl = base + "/api/v4/projects/" + encodedPath
                        + "/repository/files/" + encodedFile
                        + "?ref=" + URLEncoder.encode(branch, StandardCharsets.UTF_8);

                JsonNode fileNode = getJson(token, fileUrl);
                String b64 = fileNode.path("content").asText().replace("\n", "").replace("\r", "");
                byte[] raw = Base64.getDecoder().decode(b64);
                String content = new String(raw, StandardCharsets.UTF_8);

                var updated = DependencyManifestPatcher.patch(content, path, libName, oldVersion, newVersion);
                if (updated.isEmpty()) continue;

                String commitPayload = objectMapper.createObjectNode()
                        .put("branch", branch)
                        .put("content", updated.get())
                        .put("encoding", "text")
                        .put("commit_message", "chore: bump " + libName
                                + " from " + oldVersion + " to " + newVersion + " [OsWL]")
                        .toString();

                putJson(token, base + "/api/v4/projects/" + encodedPath
                        + "/repository/files/" + encodedFile, commitPayload);
                log.info("[GitLab] Updated {} on branch {}", path, branch);
                return true;
            } catch (Exception e) {
                log.debug("[GitLab] Skipping {} for branch {}: {}", path, branch, e.getMessage());
            }
        }
        log.warn("[GitLab] No dependency file updated — {} {} not found in manifests", libName, oldVersion);
        return false;
    }

    private List<String> discoverManifestPaths(String token, String base, String encodedPath, String ref) {
        try {
            String url = base + "/api/v4/projects/" + encodedPath
                    + "/repository/tree?recursive=true&per_page=100&pagination=keyset"
                    + "&ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8);
            JsonNode arr = getJson(token, url);
            if (!arr.isArray()) return List.of();
            List<String> paths = new ArrayList<>();
            arr.forEach(node -> {
                if (!"blob".equals(node.path("type").asText())) return;
                String path = node.path("path").asText();
                if (DependencyManifestPatcher.isManifestPath(path)) paths.add(path);
            });
            paths.sort(java.util.Comparator
                    .comparingInt((String p) -> (int) p.chars().filter(ch -> ch == '/').count())
                    .thenComparing(p -> p));
            return paths;
        } catch (Exception e) {
            log.debug("[GitLab] Tree listing failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void deleteBranchQuietly(String token, String base, String encodedPath, String branch) {
        try {
            String url = base + "/api/v4/projects/" + encodedPath + "/repository/branches/"
                    + URLEncoder.encode(branch, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .DELETE()
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[GitLab] Failed to delete branch {}: {}", branch, e.getMessage());
        }
    }

    private void assignReviewers(String token, String base, String encodedPath,
                                  int mrIid, List<String> reviewerUsernames) {
        // GitLab requires numeric user IDs — resolve each username first
        List<Long> userIds = reviewerUsernames.stream()
                .map(username -> {
                    try {
                        String url = base + "/api/v4/users?username="
                                + URLEncoder.encode(username, StandardCharsets.UTF_8);
                        JsonNode users = getJson(token, url);
                        if (users.isArray() && !users.isEmpty()) {
                            return users.get(0).path("id").asLong();
                        }
                    } catch (Exception e) {
                        log.debug("[GitLab] Could not resolve reviewer username {}: {}", username, e.getMessage());
                    }
                    return null;
                })
                .filter(id -> id != null)
                .collect(Collectors.toList());

        if (userIds.isEmpty()) return;

        String url = base + "/api/v4/projects/" + encodedPath
                + "/merge_requests/" + mrIid + "/reviewers";
        String payload = objectMapper.createObjectNode()
                .set("reviewer_ids", objectMapper.valueToTree(userIds))
                .toString();
        putJson(token, url, payload);
    }

    private String buildBase(String serverUrl) {
        return (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "")
                : DEFAULT_BASE;
    }

    private JsonNode getJson(String token, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("GitLab token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[GitLab] GET {} → HTTP {}", url, response.statusCode());
                throw new RuntimeException("GitLab API error: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitLab] GET failed: {}", url, e);
            throw new RuntimeException("Failed to call GitLab API: " + e.getMessage());
        }
    }

    private JsonNode postJson(String token, String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("GitLab token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[GitLab] POST {} → HTTP {} body: {}", url, response.statusCode(), response.body());
                throw new RuntimeException("GitLab API error: " + response.statusCode() + " — " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitLab] POST failed: {}", url, e);
            throw new RuntimeException("Failed to call GitLab API: " + e.getMessage());
        }
    }

    private void putJson(String token, String url, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(20))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new RuntimeException("GitLab token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[GitLab] PUT {} → HTTP {}", url, response.statusCode());
                throw new RuntimeException("GitLab API error: " + response.statusCode());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitLab] PUT failed: {}", url, e);
            throw new RuntimeException("Failed to call GitLab API: " + e.getMessage());
        }
    }
}
