package com.salkcoding.oswl.client;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;

/**
 * Validates a VCS access token by calling the provider's user endpoint before saving.
 * Throws IllegalStateException if the token is invalid or the server cannot be reached.
 */
@Slf4j
@Component
public class VcsTokenValidator {

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
            // Standard PAT: /personal_access_tokens/self works without scopes.
            // Fine-grained project-scope tokens may return 403 — in that case, fall back to the project list.
            client.get().uri("/api/v4/personal_access_tokens/self")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab token is invalid or lacks the required permissions.");
            }
            if (status == 403) {
                // Project-scope fine-grained tokens cannot access the user endpoint.
                // Verify through the project list.
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
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab token is invalid or expired.");
            }
            // Any 403 means the token is structurally valid but lacks the read_api scope.
            // Fine-grained tokens without API scope may follow this path.
            // Allow the connection — missing scope will surface as an error during import.
            log.warn("[VcsTokenValidator] GitLab fine-grained token may not have the read_api scope (HTTP {}). The connection will be saved.", status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab project-list fallback failed: {}", e.getMessage());
            throw new IllegalStateException("Unable to connect to GitLab to verify the token. Check the server URL and try again.");
        }
    }

    private void validateBitbucket(String serverUrl, String token, String username) {
        boolean isCloud = (serverUrl == null || serverUrl.isBlank());
        if (isCloud) {
            validateBitbucketCloud(token, username);
        } else {
            validateBitbucketServer(serverUrl, token);
        }
    }

    private void validateBitbucketCloud(String tokenOrAppPassword, String username) {
        boolean hasUsername = username != null && !username.isBlank();
        // App password → Basic auth (username required).
        // HTTP access token (ATATT, entity scope) → Bearer auth (no username).
        // NOTE: /2.0/user requires user-level auth and returns 403 for entity-scope tokens.
        // In token-only mode, verify through /2.0/workspaces.
        String authHeader = hasUsername
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + tokenOrAppPassword).getBytes())
                : "Bearer " + tokenOrAppPassword;
        RestClient client = RestClient.builder()
                .baseUrl("https://api.bitbucket.org")
                .build();
        try {
            if (hasUsername) {
                // Basic-auth app password — verify via the user endpoint.
                client.get().uri("/2.0/user")
                        .header("Authorization", authHeader)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                // Bearer token (ATATT) — entity-scope tokens cannot call /2.0/user.
                // Verify via /2.0/workspaces; 403 means the token is valid but
                // limited to a narrower scope than workspace level (project/repo token), so allow with a warning.
                try {
                    client.get().uri("/2.0/workspaces?pagelen=1")
                            .header("Authorization", authHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 403) {
                        log.warn("[VcsTokenValidator] Bitbucket HTTP access token is valid but lacks workspace scope (HTTP 403). Saving the connection.");
                        return;
                    }
                    if (e.getStatusCode().value() == 401) {
                        // Bearer auth failure (401) on /2.0/workspaces may indicate a user-level API token
                        // (replacement for App Passwords). Prompt for username input.
                        throw new IllegalStateException(
                                "The token is invalid or requires Basic authentication. " +
                                "If this is a Bitbucket API token (replacement for App Passwords), also enter the Bitbucket username.");
                    }
                    throw e;
                }
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket credentials are invalid or lack the required permissions.");
            }
            throw new IllegalStateException("Bitbucket error response: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Cloud is unreachable: {}", e.getMessage());
            throw new IllegalStateException("Unable to connect to Bitbucket to verify credentials. Please try again.");
        }
    }

    /** Validates a Personal Access Token against a Bitbucket Data Center / Server instance. */
    private void validateBitbucketServer(String serverUrl, String personalAccessToken) {
        String base = serverUrl.replaceAll("/+$", "");
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            client.get().uri("/rest/api/1.0/repos?limit=1")
                    .header("Authorization", "Bearer " + personalAccessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket Server token is invalid or lacks the required permissions.");
            }
            throw new IllegalStateException("Bitbucket Server error response: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Server is unreachable ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Unable to connect to Bitbucket Server to verify the token. Check the server URL and try again.");
        }
    }
}
