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
        // Bitbucket Cloud auth (per Atlassian official docs, 2026):
        //
        //   ATATT (Atlassian API Token, id.atlassian.com)
        //     → Basic auth, username MUST be the Atlassian email (RFC-2617).
        //     → Cannot use workspace slug as username — returns 401.
        //     → Validate via /2.0/user/permissions/workspaces (works with read:workspace:bitbucket scope).
        //
        //   ATATT (Workspace/Project/Repository HTTP Access Token)
        //     → Bearer auth only. Username field is informational (workspace slug for URL routing).
        //     → Validate via /2.0/workspaces or /2.0/repositories/{slug}.
        //
        //   ATBB (App Password) — DEPRECATED 2026-06-09.
        //     → Basic auth, username = Bitbucket account ID.
        boolean isAtatt  = tokenOrAppPassword != null && tokenOrAppPassword.startsWith("ATATT");
        boolean hasEmail = username != null && username.contains("@");
        boolean hasSlug  = username != null && !username.isBlank() && !username.contains("@");

        RestClient client = RestClient.builder()
                .baseUrl("https://api.bitbucket.org")
                .build();
        try {
            if (isAtatt && hasEmail) {
                // Atlassian API Token — Basic auth (email:token).
                String basicHeader = "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + tokenOrAppPassword).getBytes());
                try {
                    // /2.0/user/permissions/workspaces requires only read:workspace:bitbucket
                    // (does NOT require read:account that /2.0/user demands).
                    client.get().uri("/2.0/user/permissions/workspaces?pagelen=1")
                            .header("Authorization", basicHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    int s = e.getStatusCode().value();
                    if (s == 401) {
                        throw new IllegalStateException(
                                "Bitbucket authentication failed with the Atlassian API Token. " +
                                "Verify the email is correct and the token (id.atlassian.com > Security > API tokens) " +
                                "has these scopes: read:workspace:bitbucket and read:repository:bitbucket.");
                    }
                    // 403/404/410 = valid token but limited scope — accept and let listing handle it.
                    log.warn("[VcsTokenValidator] Bitbucket API Token: /user/permissions/workspaces returned HTTP {} — accepting.", s);
                }
            } else if (isAtatt && hasSlug) {
                // Slug + ATATT: must be a Workspace/Project/Repository HTTP Access Token (Bearer).
                // (API tokens cannot Basic-auth with a slug — username must be the email.)
                String bearerHeader = "Bearer " + tokenOrAppPassword;
                try {
                    client.get().uri("/2.0/repositories/" + username + "?pagelen=1")
                            .header("Authorization", bearerHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    int s = e.getStatusCode().value();
                    if (s == 401) {
                        throw new IllegalStateException(
                                "Bitbucket authentication failed. The workspace slug + ATATT combination " +
                                "only works for a Workspace HTTP Access Token (Bitbucket workspace settings > " +
                                "Access management > Access tokens). " +
                                "If this is an Atlassian API Token (id.atlassian.com), enter your Atlassian " +
                                "account email in the Username field instead of the workspace slug.");
                    }
                    if (s == 404) {
                        throw new IllegalStateException(
                                "Workspace '" + username + "' was not found or the token cannot access it. " +
                                "Verify the slug at bitbucket.org/" + username + "/ and that the access token is scoped to this workspace.");
                    }
                    log.warn("[VcsTokenValidator] Bitbucket Workspace Access Token: /repositories/{} returned HTTP {} — accepting.", username, s);
                }
            } else if (isAtatt) {
                // ATATT with no username → Workspace HTTP Access Token only (Bearer).
                String bearerHeader = "Bearer " + tokenOrAppPassword;
                try {
                    client.get().uri("/2.0/workspaces?pagelen=1")
                            .header("Authorization", bearerHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    int s = e.getStatusCode().value();
                    if (s == 401) {
                        throw new IllegalStateException(
                                "The Bitbucket HTTP Access Token is invalid or expired. " +
                                "For an Atlassian API Token, enter your Atlassian email in the Username field. " +
                                "For a Workspace HTTP Access Token, also enter the workspace slug in the Username field.");
                    }
                    log.warn("[VcsTokenValidator] Bitbucket Workspace Access Token: /workspaces returned HTTP {} — accepting (limited scope).", s);
                }
            } else {
                // App Password (ATBB) — Basic auth.
                if (username == null || username.isBlank()) {
                    throw new IllegalStateException(
                            "Bitbucket App Password requires a username (your Bitbucket account ID). " +
                            "App Passwords are deprecated — migrate to an Atlassian API Token.");
                }
                String basicHeader = "Basic " + Base64.getEncoder().encodeToString(
                        (username + ":" + tokenOrAppPassword).getBytes());
                client.get().uri("/2.0/user")
                        .header("Authorization", basicHeader)
                        .retrieve()
                        .toBodilessEntity();
            }
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket credentials are invalid or lack the required permissions.");
            }
            throw new IllegalStateException("Bitbucket returned HTTP " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Bitbucket Cloud is unreachable: {}", e.getMessage());
            throw new IllegalStateException("Unable to connect to Bitbucket. Check your network and try again.");
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
