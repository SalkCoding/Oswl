package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * GitHub REST API calls using a Personal Access Token (PAT).
 * The token is stored server-side in HttpSession ??never sent to the browser.
 */
@Slf4j
@Service
public class GitHubService {

    @Value("${oswl.github.api-base:https://api.github.com}")
    private String githubApiBase;

    private static final String USER_AGENT = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ?�?� User + Accounts ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�

    /**
     * Returns the authenticated user's login (username).
     */
    public String getUserLogin(String accessToken) {
        JsonNode user = getJson(accessToken, githubApiBase + "/user");
        return user.path("login").asText();
    }

    /**
     * Returns all available accounts: the user itself plus all organizations.
     */
    public List<GitHubAccountDto> getAccounts(String accessToken) {
        List<GitHubAccountDto> accounts = new ArrayList<>();

        // Add user account
        JsonNode user = getJson(accessToken, githubApiBase + "/user");
        accounts.add(GitHubAccountDto.builder()
                .login(user.path("login").asText())
                .type("User")
                .avatarUrl(user.path("avatar_url").asText())
                .build());

        // Add organizations
        JsonNode orgs = getJson(accessToken, githubApiBase + "/user/orgs?per_page=100");
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

    // ── Repos ────────────────────────────────────────────────────────────────

    /**
     * Returns repos for a given account (user or org), sorted by updated_at desc.
     *
     * @param accessToken GitHub access token
     * @param account     GitHub login ??if it matches the authenticated user, fetches /user/repos;
     *                    otherwise fetches /orgs/{account}/repos
     */
    public List<GitHubRepoDto> getRepos(String accessToken, String account) {
        String userLogin = getUserLogin(accessToken);
        String url;
        if (account.equalsIgnoreCase(userLogin)) {
            url = githubApiBase + "/user/repos?per_page=100&sort=updated&affiliation=owner";
        } else {
            url = githubApiBase + "/orgs/" + account + "/repos?per_page=100&sort=updated";
        }

        JsonNode repos = getJson(accessToken, url);
        List<GitHubRepoDto> result = new ArrayList<>();
        if (repos.isArray()) {
            for (JsonNode r : repos) {
                result.add(GitHubRepoDto.builder()
                        .id(r.path("id").asLong())
                        .name(r.path("name").asText())
                        .fullName(r.path("full_name").asText())
                        .defaultBranch(r.path("default_branch").asText("main"))
                        .updatedAt(r.path("updated_at").asText())
                        .isPrivate(r.path("private").asBoolean())
                        .build());
            }
        }
        return result;
    }

    // ?�?� Branches ?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�?�

    /**
     * Returns branch names for a given repo (owner/repo).
     */
    public List<String> getBranches(String accessToken, String owner, String repo) {
        String url = githubApiBase + "/repos/" + owner + "/" + repo + "/branches?per_page=100";
        JsonNode branches = getJson(accessToken, url);
        List<String> result = new ArrayList<>();
        if (branches.isArray()) {
            for (JsonNode b : branches) {
                result.add(b.path("name").asText());
            }
        }
        return result;
    }

    /**
     * Returns the ISO-8601 date of the most recent commit on the given branch.
     * Falls back to an empty string if the branch has no commits or the request fails.
     */
    public String getBranchLastCommitDate(String accessToken, String owner, String repo, String branch) {
        String url = githubApiBase + "/repos/" + owner + "/" + repo + "/commits?sha=" + branch + "&per_page=1";
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

    // ── Create Pull Request ─────────────────────────────────────────────────

    /**
     * Creates a version-bump branch and opens a pull request on GitHub.
     *
     * @param token       GitHub PAT (decrypted)
     * @param owner       repo owner (user or org login)
     * @param repo        repo name
     * @param baseBranch  target branch for the PR (e.g. "main")
     * @param libName     library identifier (e.g. "org.springframework:spring-web")
     * @param oldVersion  current version string
     * @param newVersion  target (patched) version string
     * @param prTitle     PR title
     * @param prBody      PR description / body
     * @param reviewers   optional list of reviewer logins (may be empty)
     * @return map with "prUrl" and "prNumber"
     */
    public Map<String, Object> createVersionBumpPr(String token,
                                                    String owner,
                                                    String repo,
                                                    String baseBranch,
                                                    String libName,
                                                    String oldVersion,
                                                    String newVersion,
                                                    String prTitle,
                                                    String prBody,
                                                    List<String> reviewers) {
        // 1. Get current SHA of base branch HEAD
        String branchRefUrl = githubApiBase + "/repos/" + owner + "/" + repo + "/git/ref/heads/" + baseBranch;
        JsonNode ref = getJson(token, branchRefUrl);
        String baseSha = ref.path("object").path("sha").asText();

        // 2. Create new branch
        String safeName = libName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String newBranch = "oswl/bump-" + safeName + "-" + newVersion;
        createBranch(token, owner, repo, newBranch, baseSha);

        // 3. Find and update the dependency file
        updateDependencyFile(token, owner, repo, newBranch, baseBranch, libName, oldVersion, newVersion);

        // 4. Open pull request
        String prPayload = objectMapper.createObjectNode()
                .put("title", prTitle)
                .put("body", prBody)
                .put("head", newBranch)
                .put("base", baseBranch)
                .toString();

        JsonNode pr = postJson(token, githubApiBase + "/repos/" + owner + "/" + repo + "/pulls", prPayload);
        int prNumber  = pr.path("number").asInt();
        String prUrl  = pr.path("html_url").asText();

        // 5. Optionally assign reviewers
        if (reviewers != null && !reviewers.isEmpty()) {
            try {
                String rvPayload = objectMapper.createObjectNode()
                        .set("reviewers", objectMapper.valueToTree(reviewers))
                        .toString();
                postJson(token, githubApiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/requested_reviewers", rvPayload);
            } catch (Exception e) {
                log.warn("[GitHub] Failed to assign reviewers to PR #{}: {}", prNumber, e.getMessage());
            }
        }

        log.info("[GitHub] PR #{} created: {}", prNumber, prUrl);
        return Map.of("prUrl", prUrl, "prNumber", prNumber);
    }

    private void createBranch(String token, String owner, String repo, String branch, String sha) {
        String payload = objectMapper.createObjectNode()
                .put("ref", "refs/heads/" + branch)
                .put("sha", sha)
                .toString();
        postJson(token, githubApiBase + "/repos/" + owner + "/" + repo + "/git/refs", payload);
    }

    /**
     * Finds the primary dependency manifest file, replaces oldVersion with newVersion,
     * and commits the change to the given branch.
     */
    private void updateDependencyFile(String token, String owner, String repo,
                                       String branch, String baseBranch,
                                       String libName, String oldVersion, String newVersion) {
        // Candidate dependency files to search (in priority order)
        List<String> candidates = List.of(
                "pom.xml", "build.gradle", "build.gradle.kts",
                "package.json", "requirements.txt", "pyproject.toml",
                "go.mod", "Cargo.toml"
        );

        for (String path : candidates) {
            try {
                String fileUrl = githubApiBase + "/repos/" + owner + "/" + repo
                        + "/contents/" + path + "?ref=" + baseBranch;
                JsonNode fileNode = getJson(token, fileUrl);
                String sha  = fileNode.path("sha").asText();
                String b64  = fileNode.path("content").asText().replace("\n", "").replace("\r", "");
                byte[] raw  = java.util.Base64.getDecoder().decode(b64);
                String content = new String(raw, java.nio.charset.StandardCharsets.UTF_8);

                if (!content.contains(oldVersion)) continue; // not this file

                String updated = content.replace(oldVersion, newVersion);
                String commitPayload = objectMapper.createObjectNode()
                        .put("message", "chore: bump " + libName + " from " + oldVersion + " to " + newVersion + " [OsWL]")
                        .put("content", java.util.Base64.getEncoder().encodeToString(updated.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .put("sha", sha)
                        .put("branch", branch)
                        .toString();
                putJson(token, githubApiBase + "/repos/" + owner + "/" + repo + "/contents/" + path, commitPayload);
                log.info("[GitHub] Updated {} in {}/{} on branch {}", path, owner, repo, branch);
                return;
            } catch (Exception e) {
                log.debug("[GitHub] Skipping {} for {}/{}: {}", path, owner, repo, e.getMessage());
            }
        }
        log.warn("[GitHub] No dependency file updated for {}/{} — old version '{}' not found in known manifests",
                owner, repo, oldVersion);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

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

    // ── Exception ───────────────────────────────────────────────────────────

    public static class GitHubAuthException extends RuntimeException {
        public GitHubAuthException(String message) {
            super(message);
        }
    }
}
