package com.salkcoding.oswl.client;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Base64;

/**
 * Validates VCS access tokens by calling the provider's user endpoint before saving.
 * Throws IllegalStateException on invalid token or unreachable server.
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
                throw new IllegalStateException("GitHub token is invalid or lacks required permissions.");
            }
            throw new IllegalStateException("GitHub returned an error: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Could not reach GitHub ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Could not reach GitHub to verify the token. Check the server URL and try again.");
        }
    }

    private void validateGitLab(String serverUrl, String token) {
        String base = (serverUrl != null && !serverUrl.isBlank())
                ? serverUrl.replaceAll("/+$", "")
                : "https://gitlab.com";
        RestClient client = RestClient.builder().baseUrl(base).build();
        try {
            // Standard PATs: /personal_access_tokens/self works without any scope.
            // Fine-grained project-scoped tokens may return 403 here — fall through to project list.
            client.get().uri("/api/v4/personal_access_tokens/self")
                    .header("PRIVATE-TOKEN", token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException("GitLab token is invalid or lacks required permissions.");
            }
            if (status == 403) {
                // Fine-grained tokens scoped to a project cannot access personal endpoints.
                // Validate instead via the projects list which requires read_api.
                validateGitLabViaProjects(client, token);
                return;
            }
            throw new IllegalStateException("GitLab returned an error: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Could not reach GitLab ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Could not reach GitLab to verify the token. Check the server URL and try again.");
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
            // 403 on all endpoints = token is structurally valid but lacks read_api scope.
            // Fine-grained tokens with only resource-level roles (no API scope) hit this path.
            // Allow the connection — missing scopes will surface as errors at import time.
            log.warn("[VcsTokenValidator] GitLab fine-grained token may lack read_api scope (HTTP {}). Connection will be saved anyway.", status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] GitLab project list fallback failed: {}", e.getMessage());
            throw new IllegalStateException("Could not reach GitLab to verify the token. Check the server URL and try again.");
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
        // App Password → Basic auth (username required).
        // HTTP Access Token (ATATT, entity-scoped) → Bearer auth (no username).
        // NOTE: /2.0/user requires user-level auth and returns 403 (not 401) for entity-scoped
        // tokens, so we skip it entirely for token-only mode and validate via /2.0/workspaces.
        String authHeader = hasUsername
                ? "Basic " + Base64.getEncoder().encodeToString((username + ":" + tokenOrAppPassword).getBytes())
                : "Bearer " + tokenOrAppPassword;
        RestClient client = RestClient.builder()
                .baseUrl("https://api.bitbucket.org")
                .build();
        try {
            if (hasUsername) {
                // App Password with Basic auth — validate against the user endpoint.
                client.get().uri("/2.0/user")
                        .header("Authorization", authHeader)
                        .retrieve()
                        .toBodilessEntity();
            } else {
                // Bearer token (ATATT) — entity-scoped tokens cannot call /2.0/user.
                // Validate via /2.0/workspaces; 403 means the token is valid but scoped below
                // workspace level (project/repo token) — allow with a warning.
                try {
                    client.get().uri("/2.0/workspaces?pagelen=1")
                            .header("Authorization", authHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 403) {
                        log.warn("[VcsTokenValidator] Bitbucket HTTP Access Token is valid but lacks workspace scope (HTTP 403). Connection will be saved.");
                        return;
                    }
                    if (e.getStatusCode().value() == 401) {
                        // 401 on /2.0/workspaces with Bearer auth usually means this is a
                        // user-level API Token (replacing App Passwords) that requires Basic auth.
                        // Provide a helpful message guiding the user to enter their username.
                        throw new IllegalStateException(
                                "Token invalid or requires Basic authentication. " +
                                "If this is a Bitbucket API token (replacing App Passwords), " +
                                "please also enter your Bitbucket username.");
                    }
                    throw e;
                }
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket credentials are invalid or lack required permissions.");
            }
            throw new IllegalStateException("Bitbucket returned an error: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Could not reach Bitbucket Cloud: {}", e.getMessage());
            throw new IllegalStateException("Could not reach Bitbucket to verify the credentials. Please try again.");
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
                throw new IllegalStateException("Bitbucket Server token is invalid or lacks required permissions.");
            }
            throw new IllegalStateException("Bitbucket Server returned an error: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Could not reach Bitbucket Server ({}): {}", base, e.getMessage());
            throw new IllegalStateException("Could not reach Bitbucket Server to verify the token. Check the server URL and try again.");
        }
    }
}
