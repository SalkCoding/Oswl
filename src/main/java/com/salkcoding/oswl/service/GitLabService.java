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
        String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);

        // 1. Get base branch HEAD SHA
        String branchUrl = base + "/api/v4/projects/" + encodedPath
                + "/repository/branches/" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);
        JsonNode branchInfo = getJson(token, branchUrl);
        String baseSha = branchInfo.path("commit").path("id").asText();

        // 2. Create feature branch
        String safeName  = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createBranch(token, base, encodedPath, newBranch, baseSha);

        // 3. Find and update the dependency file
        updateDependencyFile(token, base, encodedPath, newBranch, baseBranch, libName, oldVersion, newVersion);

        // 4. Create merge request
        String mrPayload = objectMapper.createObjectNode()
                .put("source_branch", newBranch)
                .put("target_branch", baseBranch)
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
        String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
        String url = base + "/api/v4/projects/" + encodedPath
                + "/repository/branches?per_page=50&order_by=updated_at&sort=desc";
        try {
            JsonNode arr = getJson(token, url);
            List<String> branches = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(b -> branches.add(b.path("name").asText()));
            }
            return branches.isEmpty() ? List.of("main") : branches;
        } catch (Exception e) {
            log.warn("[GitLab] Could not list branches for {}: {}", projectPath, e.getMessage());
            return List.of("main");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void createBranch(String token, String base, String encodedPath,
                               String branch, String ref) {
        String url = base + "/api/v4/projects/" + encodedPath + "/repository/branches";
        String payload = objectMapper.createObjectNode()
                .put("branch", branch)
                .put("ref", ref)
                .toString();
        postJson(token, url, payload);
    }

    private void updateDependencyFile(String token, String base, String encodedPath,
                                       String branch, String baseBranch,
                                       String libName, String oldVersion, String newVersion) {
        List<String> candidates = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "requirements.txt", "pyproject.toml",
                "go.mod", "Cargo.toml"
        );

        for (String path : candidates) {
            try {
                String encodedFile = URLEncoder.encode(path, StandardCharsets.UTF_8);
                String fileUrl = base + "/api/v4/projects/" + encodedPath
                        + "/repository/files/" + encodedFile
                        + "?ref=" + URLEncoder.encode(baseBranch, StandardCharsets.UTF_8);

                JsonNode fileNode = getJson(token, fileUrl);
                String b64 = fileNode.path("content").asText().replace("\n", "").replace("\r", "");
                byte[] raw = Base64.getDecoder().decode(b64);
                String content = new String(raw, StandardCharsets.UTF_8);

                if (!content.contains(oldVersion)) continue;

                String updated = content.replace(oldVersion, newVersion);
                String commitPayload = objectMapper.createObjectNode()
                        .put("branch", branch)
                        .put("content", updated)
                        .put("encoding", "text")
                        .put("commit_message", "chore: bump " + libName
                                + " from " + oldVersion + " to " + newVersion + " [OsWL]")
                        .toString();

                putJson(token, base + "/api/v4/projects/" + encodedPath
                        + "/repository/files/" + encodedFile, commitPayload);
                log.info("[GitLab] Updated {} on branch {}", path, branch);
                return;
            } catch (Exception e) {
                log.debug("[GitLab] Skipping {} for branch {}: {}", path, branch, e.getMessage());
            }
        }
        log.warn("[GitLab] No dependency file updated — old version '{}' not found in known manifests", oldVersion);
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
