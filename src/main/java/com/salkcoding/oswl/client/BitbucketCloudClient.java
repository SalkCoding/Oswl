package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bitbucket Cloud REST API helper (api.bitbucket.org/2.0).
 * Supports Atlassian Account API tokens (Basic: email + token) and
 * Workspace / Repository HTTP access tokens (Bearer).
 */
@Slf4j
@Component
public class BitbucketCloudClient {

    public static final String DEFAULT_API_BASE = "https://api.bitbucket.org/2.0";
    public static final String GIT_API_TOKEN_USERNAME = BitbucketCloudAuth.GIT_API_TOKEN_USERNAME;
    private static final String USER_AGENT = "OsWL-App/1.0";

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiBase;

    public BitbucketCloudClient() {
        this(DEFAULT_API_BASE, new ObjectMapper());
    }

    /** Test-only factory for {@link okhttp3.mockwebserver.MockWebServer}. */
    static BitbucketCloudClient forTesting(String apiBase) {
        return new BitbucketCloudClient(apiBase, new ObjectMapper());
    }

    private BitbucketCloudClient(String apiBase, ObjectMapper objectMapper) {
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.restClient = RestClient.builder().baseUrl(this.apiBase).build();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = objectMapper;
    }

    public void validateToken(String vcsUsername, String token) {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse(vcsUsername, token);
        boolean isAtatt = token != null && token.startsWith("ATATT");

        try {
            if (auth.mode() == BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT && isAtatt) {
                String basicHeader = auth.authHeader(token);
                if (auth.hasWorkspaceSlug()) {
                    validateRepoAccess(basicHeader, auth.workspaceSlug(),
                            tokenInvalidMessage(auth.email(), auth.workspaceSlug()));
                } else {
                    validateUserOrThrow(basicHeader);
                }
                return;
            }

            if (isAtatt && auth.mode() == BitbucketCloudAuth.Mode.WORKSPACE_HTTP && auth.hasWorkspaceSlug()) {
                String bearerHeader = auth.authHeader(token);
                String slug = auth.workspaceSlug();
                try {
                    restClient.get().uri("/workspaces/" + slug)
                            .header("Authorization", bearerHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    handleWorkspaceHttpError(slug, e);
                }
                validateRepoAccess(bearerHeader, slug,
                        "Bitbucket authentication failed for workspace '" + slug + "'. Check token validity and scope.");
                return;
            }

            if (isAtatt) {
                String bearerHeader = "Bearer " + token;
                try {
                    restClient.get().uri("/workspaces?pagelen=1")
                            .header("Authorization", bearerHeader)
                            .retrieve()
                            .toBodilessEntity();
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 401) {
                        throw new IllegalStateException(
                                "The Bitbucket HTTP Access Token is invalid or expired. " +
                                "For an Atlassian API Token, enter your Atlassian email and workspace slug. " +
                                "For a Workspace HTTP Access Token, enter the workspace slug.");
                    }
                    log.warn("[BitbucketCloudClient] /workspaces returned HTTP {} — accepting.", e.getStatusCode().value());
                }
                return;
            }

            if (vcsUsername == null || vcsUsername.isBlank()) {
                throw new IllegalStateException(
                        "Bitbucket App Password requires a username (your Bitbucket account ID). " +
                        "App Passwords are deprecated — migrate to an Atlassian API Token.");
            }
            validateUserOrThrow(auth.authHeader(token));
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Bitbucket credentials are invalid or lack the required permissions.");
            }
            throw new IllegalStateException("Bitbucket returned HTTP " + status);
        } catch (RestClientException e) {
            log.warn("[BitbucketCloudClient] Bitbucket Cloud is unreachable: {}", e.getMessage());
            throw new IllegalStateException("Unable to connect to Bitbucket. Check your network and try again.");
        }
    }

    public List<QuickImportRepoDto> listRepositories(String vcsUsername, String token) throws Exception {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse(vcsUsername, token);
        boolean isAtatt = token != null && token.startsWith("ATATT");

        if (isAtatt && auth.mode() == BitbucketCloudAuth.Mode.ATLASSIAN_ACCOUNT) {
            if (!auth.hasWorkspaceSlug()) {
                throw new IllegalStateException(
                        "Atlassian API Token requires both your Atlassian email and workspace slug. " +
                        "Reconnect in Settings → VCS with email + slug and your id.atlassian.com token.");
            }
            String basicHeader = auth.authHeader(token);
            List<QuickImportRepoDto> repos = fetchRepoPage(
                    apiBase + "/repositories/" + auth.workspaceSlug() + "?pagelen=100&sort=-updated_on",
                    basicHeader);
            if (!repos.isEmpty()) return repos;
            throw new IllegalStateException(
                    "Could not list repositories for workspace '" + auth.workspaceSlug() + "' with the Atlassian API Token. " +
                    "Verify workspace membership and token scopes (read:repository:bitbucket).");

        } else if (isAtatt && auth.mode() == BitbucketCloudAuth.Mode.WORKSPACE_HTTP && auth.hasWorkspaceSlug()) {
            String bearerHeader = auth.authHeader(token);
            String slug = auth.workspaceSlug();
            List<QuickImportRepoDto> repos = fetchRepoPage(
                    apiBase + "/repositories/" + slug + "?pagelen=100&sort=-updated_on",
                    bearerHeader);
            if (!repos.isEmpty()) return repos;

            List<QuickImportRepoDto> fromWorkspaces = fetchReposViaWorkspaceList(bearerHeader);
            if (!fromWorkspaces.isEmpty()) return fromWorkspaces;

            throw new IllegalStateException(
                    "Could not list repositories for workspace '" + slug + "'. " +
                    "Verify the workspace slug, token scopes (Repositories: Read), and that the token is a " +
                    "Workspace HTTP Access Token from Bitbucket workspace settings → Access management → Access tokens.");

        } else if (isAtatt) {
            String bearerHeader = "Bearer " + token;
            List<QuickImportRepoDto> fromWorkspaces = fetchReposViaWorkspaceList(bearerHeader);
            if (!fromWorkspaces.isEmpty()) return fromWorkspaces;
            throw new IllegalStateException(
                    "Could not list repositories with the Bitbucket HTTP Access Token. " +
                    "Provide the workspace slug to target a specific workspace.");

        } else {
            if (vcsUsername == null || vcsUsername.isBlank()) {
                throw new IllegalStateException(
                        "Bitbucket App Password requires a username. " +
                        "Enter your Bitbucket account ID in the Username field, or migrate to an Atlassian API Token.");
            }
            String basicHeader = auth.authHeader(token);
            if (auth.hasWorkspaceSlug()) {
                List<QuickImportRepoDto> repos = fetchRepoPage(
                        apiBase + "/repositories/" + auth.workspaceSlug() + "?pagelen=100&sort=-updated_on",
                        basicHeader);
                if (!repos.isEmpty()) return repos;
            } else if (!vcsUsername.contains("@")) {
                List<QuickImportRepoDto> repos = fetchRepoPage(
                        apiBase + "/repositories/" + vcsUsername + "?pagelen=100&sort=-updated_on",
                        basicHeader);
                if (!repos.isEmpty()) return repos;
            }
            List<QuickImportRepoDto> fromWorkspaces = fetchReposViaWorkspaceList(basicHeader);
            if (!fromWorkspaces.isEmpty()) return fromWorkspaces;
            throw new IllegalStateException(
                    "Could not list repositories with the Bitbucket App Password. " +
                    "Ensure the App Password has 'Repositories: Read' and 'Account: Read' scopes.");
        }
    }

    /**
     * Credentials for {@code git clone} without embedding secrets in the URL.
     */
    public com.salkcoding.oswl.service.git.GitCloneCredentials cloneCredentials(String vcsUsername, String token) {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse(vcsUsername, token);
        return new com.salkcoding.oswl.service.git.GitCloneCredentials(auth.cloneUsername(token), token);
    }

    /**
     * @deprecated Use a clean HTTPS URL plus {@link #cloneCredentials(String, String)} and {@code GitCloneExecutor}.
     */
    @Deprecated
    public String buildCloneAuthUrl(String vcsUsername, String token, String host, String repoPath) {
        BitbucketCloudAuth auth = BitbucketCloudAuth.parse(vcsUsername, token);
        String encodedToken = URLEncoder.encode(token != null ? token : "", StandardCharsets.UTF_8);
        String cloneUser = URLEncoder.encode(auth.cloneUsername(token), StandardCharsets.UTF_8);
        return "https://" + cloneUser + ":" + encodedToken + "@" + host + repoPath + ".git";
    }

    /** Returns true when Bitbucket accepts the credentials (any 2xx from repo list or user endpoint). */
    public boolean credentialsAccepted(String vcsUsername, String token) {
        try {
            validateToken(vcsUsername, token);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private void validateUserOrThrow(String basicHeader) {
        try {
            restClient.get().uri("/user")
                    .header("Authorization", basicHeader)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == 401) {
                throw new IllegalStateException(
                        "Bitbucket authentication failed with the Atlassian API Token. " +
                        "Verify the email and token (id.atlassian.com → Security → API tokens). " +
                        "Create the token with Bitbucket scopes and copy it immediately after creation.");
            }
            log.warn("[BitbucketCloudClient] /user returned HTTP {} — accepting.", e.getStatusCode().value());
        }
    }

    private void validateRepoAccess(String authHeader, String workspaceSlug, String authFailureMessage) {
        try {
            restClient.get().uri("/repositories/" + workspaceSlug + "?pagelen=1")
                    .header("Authorization", authHeader)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new IllegalStateException(authFailureMessage);
            }
            if (status == 404) {
                throw new IllegalStateException(
                        "Workspace '" + workspaceSlug + "' is reachable but no repositories are visible. " +
                        "Check repository permissions for this token.");
            }
            log.warn("[BitbucketCloudClient] /repositories/{} returned HTTP {} — accepting.", workspaceSlug, status);
        }
    }

    private void handleWorkspaceHttpError(String slug, HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        if (status == 404) {
            throw new IllegalStateException(
                    "Workspace '" + slug + "' was not found. Verify the workspace slug at bitbucket.org/" + slug + "/.");
        }
        if (status == 401) {
            throw new IllegalStateException(
                    "Bitbucket authentication failed for workspace '" + slug + "'. " +
                    "If this is an Atlassian API Token from id.atlassian.com, enter your Atlassian email " +
                    "and workspace slug (not slug alone). For a Workspace HTTP Access Token, create one in " +
                    "Bitbucket workspace settings → Access management → Access tokens.");
        }
        log.warn("[BitbucketCloudClient] /workspaces/{} returned HTTP {} — continuing.", slug, status);
    }

    private static String tokenInvalidMessage(String email, String workspaceSlug) {
        return "Bitbucket authentication failed with the Atlassian API Token for " + email + " / " + workspaceSlug + ". "
                + "Verify the email matches your Atlassian account, the workspace slug is correct, and the token "
                + "was created at id.atlassian.com → Security → API tokens with Bitbucket scopes "
                + "(read:repository:bitbucket, read:workspace:bitbucket). "
                + "If the token shows 'Never accessed' or you copied it incompletely, create a new token.";
    }

    private List<QuickImportRepoDto> fetchReposViaWorkspaceList(String authHeader) throws Exception {
        HttpRequest wsReq = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/workspaces?pagelen=100"))
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> wsResp = httpClient.send(wsReq, HttpResponse.BodyHandlers.ofString());
        if (wsResp.statusCode() != 200) {
            log.warn("[BitbucketCloudClient] /workspaces returned HTTP {}", wsResp.statusCode());
            return List.of();
        }
        JsonNode workspaces = objectMapper.readTree(wsResp.body()).path("values");
        if (!workspaces.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode ws : workspaces) {
            String slug = ws.path("slug").asText();
            if (!slug.isBlank()) {
                result.addAll(fetchRepoPage(
                        apiBase + "/repositories/" + slug + "?pagelen=100&sort=-updated_on",
                        authHeader));
            }
        }
        return result;
    }

    private List<QuickImportRepoDto> fetchRepoPage(String url, String authHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.debug("[BitbucketCloudClient] GET {} → HTTP {}", url, resp.statusCode());
            return List.of();
        }
        JsonNode values = objectMapper.readTree(resp.body()).path("values");
        if (!values.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode r : values) {
            result.add(new QuickImportRepoDto(
                    r.path("name").asText(),
                    r.path("full_name").asText(),
                    r.path("links").path("html").path("href").asText(),
                    r.path("mainbranch").path("name").asText("main"),
                    r.path("is_private").asBoolean(false),
                    r.path("updated_on").asText()));
        }
        log.debug("[BitbucketCloudClient] GET {} → HTTP {} repos={}", url, resp.statusCode(), result.size());
        return result;
    }
}
