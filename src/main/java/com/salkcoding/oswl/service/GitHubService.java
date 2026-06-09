package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GitHub REST API client using a Personal Access Token (PAT).
 * Supports GitHub.com (SaaS) and GitHub Enterprise (on-premise) via per-connection {@code serverUrl}.
 */
@Slf4j
@Service
public class GitHubService {

    @Value("${oswl.github.api-base:https://api.github.com}")
    private String defaultGithubApiBase;

    private static final String DEFAULT_WEB_BASE = "https://github.com";
    private static final String USER_AGENT = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── URL resolution ───────────────────────────────────────────────────────

    /** REST API base: per-connection serverUrl, else deployment default (OSWL_GITHUB_API_BASE). */
    public String resolveApiBase(String serverUrl) {
        if (serverUrl != null && !serverUrl.isBlank()) {
            String base = serverUrl.trim().replaceAll("/+$", "");
            if (base.endsWith("/api/v3")) return base;
            return base + "/api/v3";
        }
        return defaultGithubApiBase.replaceAll("/+$", "");
    }

    /** Browser/web origin for repo links (Quick Import, PR URLs). */
    public String resolveWebBase(String serverUrl) {
        if (serverUrl != null && !serverUrl.isBlank()) {
            String base = serverUrl.trim().replaceAll("/+$", "");
            if (base.endsWith("/api/v3")) {
                return base.substring(0, base.length() - "/api/v3".length());
            }
            return base;
        }
        String apiBase = defaultGithubApiBase.replaceAll("/+$", "");
        if (apiBase.endsWith("/api/v3")) {
            return apiBase.substring(0, apiBase.length() - "/api/v3".length());
        }
        return DEFAULT_WEB_BASE;
    }

    // ── Public API (serverUrl null = SaaS / deployment default) ──────────────

    public String getUserLogin(String accessToken) {
        return getUserLogin(accessToken, null);
    }

    public String getUserLogin(String accessToken, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        JsonNode user = getJson(accessToken, apiBase + "/user");
        return user.path("login").asText();
    }

    public List<GitHubAccountDto> getAccounts(String accessToken) {
        return getAccounts(accessToken, null);
    }

    public List<GitHubAccountDto> getAccounts(String accessToken, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        List<GitHubAccountDto> accounts = new ArrayList<>();

        JsonNode user = getJson(accessToken, apiBase + "/user");
        accounts.add(GitHubAccountDto.builder()
                .login(user.path("login").asText())
                .type("User")
                .avatarUrl(user.path("avatar_url").asText())
                .build());

        JsonNode orgs = getJson(accessToken, apiBase + "/user/orgs?per_page=100");
        if (orgs.isArray()) {
            for (JsonNode org : orgs) {
                accounts.add(GitHubAccountDto.builder()
                        .login(org.path("login").asText())
                        .type("Organization")
                        .avatarUrl(org.path("avatar_url").asText())
                        .build());
            }
        }
        return accounts;
    }

    public List<GitHubRepoDto> listAllUserRepos(String accessToken) {
        return listAllUserRepos(accessToken, null);
    }

    public List<GitHubRepoDto> listAllUserRepos(String accessToken, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        String url = apiBase + "/user/repos?per_page=100&sort=updated";
        JsonNode repos = getJson(accessToken, url);
        List<GitHubRepoDto> result = new ArrayList<>();
        if (repos.isArray()) {
            for (JsonNode r : repos) {
                result.add(toRepoDto(r));
            }
        }
        return result;
    }

    public List<GitHubRepoDto> getRepos(String accessToken, String account) {
        return getRepos(accessToken, account, null);
    }

    public List<GitHubRepoDto> getRepos(String accessToken, String account, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        String userLogin = getUserLogin(accessToken, serverUrl);
        String url = account.equalsIgnoreCase(userLogin)
                ? apiBase + "/user/repos?per_page=100&sort=updated&affiliation=owner"
                : apiBase + "/orgs/" + account + "/repos?per_page=100&sort=updated";

        JsonNode repos = getJson(accessToken, url);
        List<GitHubRepoDto> result = new ArrayList<>();
        if (repos.isArray()) {
            for (JsonNode r : repos) {
                result.add(toRepoDto(r));
            }
        }
        return result;
    }

    public List<String> getBranches(String accessToken, String owner, String repo) {
        return getBranches(accessToken, owner, repo, null);
    }

    public List<String> getBranches(String accessToken, String owner, String repo, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        String defaultBranch = fetchRepoDefaultBranch(accessToken, owner, repo, apiBase);
        String url = apiBase + "/repos/" + owner + "/" + repo + "/branches?per_page=100";
        JsonNode branches = getJson(accessToken, url);
        List<String> result = new ArrayList<>();
        if (branches.isArray()) {
            for (JsonNode b : branches) {
                result.add(b.path("name").asText());
            }
        }
        if (defaultBranch != null && result.contains(defaultBranch)) {
            result.remove(defaultBranch);
            result.addFirst(defaultBranch);
        }
        return result;
    }

    public String getBranchLastCommitDate(String accessToken, String owner, String repo, String branch) {
        return getBranchLastCommitDate(accessToken, owner, repo, branch, null);
    }

    public String getBranchLastCommitDate(String accessToken, String owner, String repo, String branch, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        String url = apiBase + "/repos/" + owner + "/" + repo + "/commits?sha=" + branch + "&per_page=1";
        JsonNode commits = getJson(accessToken, url);
        if (commits.isArray() && !commits.isEmpty()) {
            JsonNode commit = commits.get(0).path("commit");
            String date = commit.path("committer").path("date").asText("");
            if (date.isEmpty()) {
                date = commit.path("author").path("date").asText("");
            }
            return date;
        }
        return "";
    }

    public Map<String, Object> createVersionBumpPr(String token, String owner, String repo,
                                                      String baseBranch, String libName,
                                                      String oldVersion, String newVersion,
                                                      String prTitle, String prBody,
                                                      List<String> reviewers) {
        return createVersionBumpPr(token, owner, repo, baseBranch, libName, oldVersion, newVersion,
                prTitle, prBody, reviewers, null);
    }

    public Map<String, Object> createVersionBumpPr(String token, String owner, String repo,
                                                      String baseBranch, String libName,
                                                      String oldVersion, String newVersion,
                                                      String prTitle, String prBody,
                                                      List<String> reviewers, String serverUrl) {
        String apiBase = resolveApiBase(serverUrl);
        String resolvedBase = resolveBaseBranch(token, owner, repo, baseBranch, apiBase);

        JsonNode ref = getJson(token, branchHeadRefUrl(owner, repo, resolvedBase, apiBase));
        String baseSha = ref.path("object").path("sha").asText();

        String safeName = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        ensureBranch(token, owner, repo, newBranch, baseSha, apiBase);

        boolean manifestUpdated = updateDependencyFile(
                token, owner, repo, newBranch, resolvedBase, baseSha, libName, oldVersion, newVersion, apiBase);
        if (!manifestUpdated) {
            deleteBranchQuietly(token, owner, repo, newBranch, apiBase);
            throw new IllegalStateException(
                    "Could not find " + libName + " at version " + oldVersion
                            + " in any dependency manifest in this repository. "
                            + "OsWL searches common manifest files (package.json, pom.xml, build.gradle, etc.) "
                            + "including subdirectories. If the dependency is declared elsewhere, bump the version manually.");
        }

        String prPayload = objectMapper.createObjectNode()
                .put("title", prTitle)
                .put("body", prBody)
                .put("head", newBranch)
                .put("base", resolvedBase)
                .toString();

        JsonNode pr = postJson(token, apiBase + "/repos/" + owner + "/" + repo + "/pulls", prPayload);
        int prNumber = pr.path("number").asInt();
        String prUrl = pr.path("html_url").asText();

        if (reviewers != null && !reviewers.isEmpty()) {
            try {
                String rvPayload = objectMapper.createObjectNode()
                        .set("reviewers", objectMapper.valueToTree(reviewers))
                        .toString();
                postJson(token, apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/requested_reviewers", rvPayload);
            } catch (Exception e) {
                log.warn("[GitHub] Failed to assign reviewers to PR #{}: {}", prNumber, e.getMessage());
            }
        }

        log.info("[GitHub] PR #{} created: {}", prNumber, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prNumber);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private GitHubRepoDto toRepoDto(JsonNode r) {
        return GitHubRepoDto.builder()
                .id(r.path("id").asLong())
                .name(r.path("name").asText())
                .fullName(r.path("full_name").asText())
                .defaultBranch(r.path("default_branch").asText("main"))
                .updatedAt(r.path("updated_at").asText())
                .isPrivate(r.path("private").asBoolean())
                .build();
    }

    private void ensureBranch(String token, String owner, String repo, String branch, String sha, String apiBase) {
        if (branchHeadExists(token, owner, repo, branch, apiBase)) {
            log.info("[GitHub] Branch {} already exists on {}/{} — deleting before recreate", branch, owner, repo);
            deleteBranchQuietly(token, owner, repo, branch, apiBase);
            for (int attempt = 1; attempt <= 5; attempt++) {
                if (!branchHeadExists(token, owner, repo, branch, apiBase)) break;
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        String payload = objectMapper.createObjectNode()
                .put("ref", "refs/heads/" + branch)
                .put("sha", sha)
                .toString();
        postJson(token, apiBase + "/repos/" + owner + "/" + repo + "/git/refs", payload);
    }

    private String resolveBaseBranch(String token, String owner, String repo, String requested, String apiBase) {
        if (requested != null && !requested.isBlank() && branchHeadExists(token, owner, repo, requested, apiBase)) {
            return requested;
        }
        String defaultBranch = fetchRepoDefaultBranch(token, owner, repo, apiBase);
        if (defaultBranch != null && branchHeadExists(token, owner, repo, defaultBranch, apiBase)) {
            if (requested != null && !requested.isBlank() && !requested.equals(defaultBranch)) {
                log.info("[GitHub] Base branch '{}' not found on {}/{} — using default '{}'",
                        requested, owner, repo, defaultBranch);
            }
            return defaultBranch;
        }
        for (String candidate : List.of("main", "master")) {
            if (branchHeadExists(token, owner, repo, candidate, apiBase)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Could not find base branch '" + requested + "' or a default branch in " + owner + "/" + repo);
    }

    private String fetchRepoDefaultBranch(String token, String owner, String repo, String apiBase) {
        try {
            JsonNode repoNode = getJson(token, apiBase + "/repos/" + owner + "/" + repo);
            String def = repoNode.path("default_branch").asText("");
            return def.isBlank() ? null : def;
        } catch (Exception e) {
            log.debug("[GitHub] Could not read default_branch for {}/{}: {}", owner, repo, e.getMessage());
            return null;
        }
    }

    private boolean branchHeadExists(String token, String owner, String repo, String branch, String apiBase) {
        try {
            getJson(token, branchHeadRefUrl(owner, repo, branch, apiBase));
            return true;
        } catch (GitHubAuthException e) {
            return false;
        }
    }

    private String branchHeadRefUrl(String owner, String repo, String branch, String apiBase) {
        String ref = "heads/" + branch;
        return apiBase + "/repos/" + owner + "/" + repo + "/git/ref/"
                + URLEncoder.encode(ref, StandardCharsets.UTF_8);
    }

    private boolean updateDependencyFile(String token, String owner, String repo,
                                          String branch, String baseBranch, String baseTreeSha,
                                          String libName, String oldVersion, String newVersion,
                                          String apiBase) {
        List<String> paths = discoverManifestPaths(token, owner, repo, baseTreeSha, apiBase);
        if (paths.isEmpty()) {
            paths = List.of(
                    "package.json", "package-lock.json", "pom.xml", "build.gradle", "build.gradle.kts",
                    "requirements.txt", "pyproject.toml", "go.mod", "Cargo.toml"
            );
        }

        for (String path : paths) {
            try {
                String fileUrl = apiBase + "/repos/" + owner + "/" + repo
                        + "/contents/" + path + "?ref=" + branch;
                JsonNode fileNode = getJson(token, fileUrl);
                String sha = fileNode.path("sha").asText();
                String b64 = fileNode.path("content").asText().replace("\n", "").replace("\r", "");
                byte[] raw = java.util.Base64.getDecoder().decode(b64);
                String content = new String(raw, StandardCharsets.UTF_8);

                Optional<String> updated = DependencyManifestPatcher.patch(
                        content, path, libName, oldVersion, newVersion);
                if (updated.isEmpty()) continue;

                String commitPayload = objectMapper.createObjectNode()
                        .put("message", "chore: bump " + libName + " from " + oldVersion + " to " + newVersion + " [OsWL]")
                        .put("content", java.util.Base64.getEncoder().encodeToString(
                                updated.get().getBytes(StandardCharsets.UTF_8)))
                        .put("sha", sha)
                        .put("branch", branch)
                        .toString();
                putJson(token, apiBase + "/repos/" + owner + "/" + repo + "/contents/" + path, commitPayload);
                log.info("[GitHub] Updated {} in {}/{} on branch {}", path, owner, repo, branch);
                return true;
            } catch (Exception e) {
                log.debug("[GitHub] Skipping {} for {}/{}: {}", path, owner, repo, e.getMessage());
            }
        }
        log.warn("[GitHub] No dependency file updated for {}/{} — {} {} not found in manifests",
                owner, repo, libName, oldVersion);
        return false;
    }

    private List<String> discoverManifestPaths(String token, String owner, String repo, String treeSha, String apiBase) {
        try {
            String url = apiBase + "/repos/" + owner + "/" + repo + "/git/trees/" + treeSha + "?recursive=1";
            JsonNode tree = getJson(token, url);
            Set<String> paths = new LinkedHashSet<>();
            for (JsonNode entry : tree.path("tree")) {
                if (!"blob".equals(entry.path("type").asText())) continue;
                String path = entry.path("path").asText();
                if (DependencyManifestPatcher.isManifestPath(path)) {
                    paths.add(path);
                }
            }
            return paths.stream()
                    .sorted(Comparator
                            .comparingInt((String p) -> (int) p.chars().filter(ch -> ch == '/').count())
                            .thenComparing(p -> p))
                    .toList();
        } catch (Exception e) {
            log.debug("[GitHub] Tree listing failed for {}/{}: {}", owner, repo, e.getMessage());
            return List.of();
        }
    }

    private void deleteBranchQuietly(String token, String owner, String repo, String branch, String apiBase) {
        try {
            String url = apiBase + "/repos/" + owner + "/" + repo + "/git/refs/heads/" + branch;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .DELETE()
                    .timeout(Duration.ofSeconds(15))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.debug("[GitHub] Failed to delete branch {} on {}/{}: {}", branch, owner, repo, e.getMessage());
        }
    }

    private JsonNode getJson(String accessToken, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                throw new GitHubAuthException("GitHub access token is invalid or expired");
            }
            if (response.statusCode() >= 400) {
                log.warn("[GitHub] API error {} for URL: {}", response.statusCode(), url);
                throw new GitHubAuthException("GitHub API error: " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (GitHubAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitHub] Request failed for URL: {}", url, e);
            throw new GitHubAuthException("Failed to call GitHub API");
        }
    }

    private JsonNode postJson(String token, String url, String jsonBody) {
        return sendWithBody(token, url, jsonBody, "POST");
    }

    private JsonNode putJson(String token, String url, String jsonBody) {
        return sendWithBody(token, url, jsonBody, "PUT");
    }

    private JsonNode sendWithBody(String token, String url, String jsonBody, String method) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(20))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new GitHubAuthException("GitHub access token is invalid or expired");
            if (response.statusCode() >= 400) {
                log.warn("[GitHub] {} {} → {} body={}", method, url, response.statusCode(), response.body());
                throw new GitHubAuthException("GitHub API error " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (GitHubAuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("[GitHub] {} failed for {}: {}", method, url, e.getMessage());
            throw new GitHubAuthException("Failed to call GitHub API");
        }
    }

    public static class GitHubAuthException extends RuntimeException {
        public GitHubAuthException(String message) {
            super(message);
        }
    }
}
