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
            client.get().uri("/api/v4/user")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("GitLab token is invalid or lacks required permissions.");
            }
            throw new IllegalStateException("GitLab returned an error: " + status);
        } catch (RestClientException e) {
            log.warn("[VcsTokenValidator] Could not reach GitLab ({}): {}", base, e.getMessage());
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

    private void validateBitbucketCloud(String appPassword, String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException("Bitbucket Cloud requires a username.");
        }
        String credentials = Base64.getEncoder()
                .encodeToString((username + ":" + appPassword).getBytes());
        RestClient client = RestClient.builder()
                .baseUrl("https://api.bitbucket.org")
                .build();
        try {
            client.get().uri("/2.0/user")
                    .header("Authorization", "Basic " + credentials)
                    .retrieve()
                    .toBodilessEntity();
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
