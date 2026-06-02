package com.salkcoding.oswl.client;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Validates a VCS access token by calling the provider's user endpoint before saving.
 * Throws IllegalStateException if the token is invalid or the server cannot be reached.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VcsTokenValidator {

    private final BitbucketCloudClient bitbucketCloudClient;

    public void validate(VcsProvider provider, String serverUrl, String accessToken, String vcsUsername) {
        switch (provider) {
            case GITHUB    -> validateGitHub(serverUrl, accessToken);
            case GITLAB    -> validateGitLab(serverUrl, accessToken);
            case BITBUCKET -> validateBitbucket(serverUrl, accessToken, vcsUsername);
        }
    }

    private void validateGitHub(String serverUrl, String token) {
        String base = (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "") + "/api/v3"
                : "https://api.github.com";
        RestClient client = RestClient.builder()
                .baseUrl(base)
                .defaultHeader("Accept", "application/vnd.github+json")
                .build();
        try {
            client.get().uri("/user")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[VcsTokenValidator] GitHub token validated ({})", base);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("GitHub token is invalid or lacks the required permissions.");
            }
            throw new IllegalStateException("GitHub error response: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitHub is unreachable ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Unable to connect to GitHub to verify the token. Check the server URL and try again.");
        }
    }

    private void validateGitLab(String serverUrl, String token) {
        String base = (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "")
                : "https://gitlab.com";
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            client.get().uri("/api/v4/personal_access_tokens/self")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[VcsTokenValidator] GitLab token validated via /personal_access_tokens/self ({})", base);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab token is invalid or lacks the required permissions.");
            }
            if (status == 403) {
                validateGitLabViaProjects(client, token);
                return;
            }
            throw new IllegalStateException("GitLab error response: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab is unreachable ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Unable to connect to GitLab to verify the token. Check the server URL and try again.");
        }
    }

    private void validateGitLabViaProjects(RestClient client, String token) {
        try {
            client.get().uri("/api/v4/projects?membership=true&per_page=1")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[VcsTokenValidator] GitLab token validated via project list fallback");
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab token is invalid or expired.");
            }
            log.warn("[VcsTokenValidator] GitLab fine-grained token may not have the read_api scope (HTTP {}). The connection will be saved.", status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab project-list fallback failed: {}", e.getMessage());
            throw new IllegalStateException("Unable to connect to GitLab to verify the token. Check the server URL and try again.");
        }
    }

    private void validateBitbucket(String serverUrl, String token, String username) {
        if (serverUrl == null || serverUrl.isBlank()) {
            bitbucketCloudClient.validateToken(username, token);
            log.debug("[VcsTokenValidator] Bitbucket Cloud token validated");
            return;
        }
        String normalized = serverUrl.trim().replaceAll("/+$", "").toLowerCase();
        if (normalized.equals("https://bitbucket.org") || normalized.equals("http://bitbucket.org")) {
            bitbucketCloudClient.validateToken(username, token);
            log.debug("[VcsTokenValidator] Bitbucket Cloud token validated");
        } else {
            validateBitbucketServer(serverUrl, token);
        }
    }

    /** Validates a Personal Access Token against a Bitbucket Data Center / Server instance. */
    private void validateBitbucketServer(String serverUrl, String personalAccessToken) {
        String base = serverUrl.replaceAll("/+$", "");
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            client.get().uri("/rest/api/1.0/projects?limit=1")
                    .header("Authorization", "Bearer " + personalAccessToken)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("[VcsTokenValidator] Bitbucket Server token validated ({})", base);
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException(
                        "Bitbucket Server token is invalid or lacks the required permissions. " +
                        "Create a Personal Access Token with Repository read/write access.");
            }
            throw new IllegalStateException("Bitbucket Server error response: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Server is unreachable ({}): {}", base, e.getMessage());
            throw new IllegalStateException(
                    "Unable to connect to Bitbucket Server. Check the server URL (e.g. https://bitbucket.example.com) and try again.");
        }
    }
}
