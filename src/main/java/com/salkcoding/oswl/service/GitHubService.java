package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API calls using a Personal Access Token (PAT).
 * The token is stored server-side in HttpSession — never sent to the browser.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubService {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String USER_AGENT      = "OsWL-App/1.0";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── User + Accounts ──────────────────────────────────────────────────────

    /**
     * Returns the authenticated user's login (username).
     */
    public String getUserLogin(String accessToken) {
        JsonNode user = getJson(accessToken, GITHUB_API_BASE + "/user");
        return user.path("login").asText();
    }

    /**
     * Returns all available accounts: the user itself plus all organizations.
     */
    public List<GitHubAccountDto> getAccounts(String accessToken) {
        List<GitHubAccountDto> accounts = new ArrayList<>();

        // Add user account
        JsonNode user = getJson(accessToken, GITHUB_API_BASE + "/user");
        accounts.add(GitHubAccountDto.builder()
                .login(user.path("login").asText())
                .type("User")
                .avatarUrl(user.path("avatar_url").asText())
                .build());

        // Add organizations
        JsonNode orgs = getJson(accessToken, GITHUB_API_BASE + "/user/orgs?per_page=100");
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

    // ── Repos ─────────────────────────────────────────────────────────────────

    /**
     * Returns repos for a given account (user or org), sorted by updated_at desc.
     *
     * @param accessToken GitHub access token
     * @param account     GitHub login — if it matches the authenticated user, fetches /user/repos;
     *                    otherwise fetches /orgs/{account}/repos
     */
    public List<GitHubRepoDto> getRepos(String accessToken, String account) {
        String userLogin = getUserLogin(accessToken);
        String url;
        if (account.equalsIgnoreCase(userLogin)) {
            url = GITHUB_API_BASE + "/user/repos?per_page=100&sort=updated&affiliation=owner";
        } else {
            url = GITHUB_API_BASE + "/orgs/" + account + "/repos?per_page=100&sort=updated";
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

    // ── Branches ──────────────────────────────────────────────────────────────

    /**
     * Returns branch names for a given repo (owner/repo).
     */
    public List<String> getBranches(String accessToken, String owner, String repo) {
        String url = GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/branches?per_page=100";
        JsonNode branches = getJson(accessToken, url);
        List<String> result = new ArrayList<>();
        if (branches.isArray()) {
            for (JsonNode b : branches) {
                result.add(b.path("name").asText());
            }
        }
        return result;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

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

    // ── Exception ─────────────────────────────────────────────────────────────

    public static class GitHubAuthException extends RuntimeException {
        public GitHubAuthException(String message) {
            super(message);
        }
    }
}
