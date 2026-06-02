package com.salkcoding.oswl.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Parses Bitbucket Cloud {@code vcsUsername} and builds the correct Authorization header.
 *
 * <p>Stored {@code vcsUsername} formats:
 * <ul>
 *   <li>{@code email|workspaceSlug} — Atlassian Account API Token (id.atlassian.com), Basic auth</li>
 *   <li>{@code workspaceSlug} — Workspace HTTP Access Token, Bearer auth</li>
 *   <li>{@code email} — Atlassian Account API Token without workspace (validation only)</li>
 * </ul>
 */
public record BitbucketCloudAuth(String email, String workspaceSlug, Mode mode) {

    public static final String GIT_API_TOKEN_USERNAME = "x-bitbucket-api-token-auth";

    public enum Mode {
        /** Basic auth: Atlassian email + token (id.atlassian.com API token). */
        ATLASSIAN_ACCOUNT,
        /** Bearer auth: Workspace / Project / Repository HTTP Access Token. */
        WORKSPACE_HTTP
    }

    private static final char DELIMITER = '|';

    public static BitbucketCloudAuth parse(String vcsUsername, String token) {
        String username = vcsUsername != null ? vcsUsername.trim() : "";
        boolean isAtatt = token != null && token.startsWith("ATATT");

        if (username.contains(String.valueOf(DELIMITER))) {
            int sep = username.indexOf(DELIMITER);
            String email = username.substring(0, sep).trim();
            String slug  = username.substring(sep + 1).trim();
            if (!email.isBlank() && email.contains("@") && !slug.isBlank()) {
                return new BitbucketCloudAuth(email, slug, Mode.ATLASSIAN_ACCOUNT);
            }
        }

        if (username.contains("@")) {
            return new BitbucketCloudAuth(username, null, Mode.ATLASSIAN_ACCOUNT);
        }

        if (!username.isBlank()) {
            if (isAtatt) {
                return new BitbucketCloudAuth(null, username, Mode.WORKSPACE_HTTP);
            }
            // App Password (ATBB) — Basic auth with Bitbucket account ID as username.
            return new BitbucketCloudAuth(username, username, Mode.ATLASSIAN_ACCOUNT);
        }

        if (isAtatt) {
            return new BitbucketCloudAuth(null, null, Mode.WORKSPACE_HTTP);
        }
        return new BitbucketCloudAuth(null, null, Mode.WORKSPACE_HTTP);
    }

    public String authHeader(String token) {
        return switch (mode) {
            case ATLASSIAN_ACCOUNT -> {
                if (email == null || email.isBlank()) {
                    throw new IllegalStateException(
                            "Atlassian account email is required for this Bitbucket token. " +
                            "Enter your Atlassian email and workspace slug (e.g. salkcoding).");
                }
                yield basicHeader(email, token);
            }
            case WORKSPACE_HTTP -> "Bearer " + token;
        };
    }

    /**
     * Username for git clone HTTPS URL.
     * Atlassian API tokens use the static username documented by Atlassian:
     * {@code x-bitbucket-api-token-auth}.
     */
    public String cloneUsername(String token) {
        if (mode == Mode.ATLASSIAN_ACCOUNT && token != null && token.startsWith("ATATT")) {
            return GIT_API_TOKEN_USERNAME;
        }
        if (workspaceSlug != null && !workspaceSlug.isBlank()) {
            return workspaceSlug;
        }
        return "x-token-auth";
    }

    public boolean hasWorkspaceSlug() {
        return workspaceSlug != null && !workspaceSlug.isBlank();
    }

    public boolean isAtlassianAccountToken(String token) {
        return token != null && token.startsWith("ATATT") && mode == Mode.ATLASSIAN_ACCOUNT;
    }

    private static String basicHeader(String user, String token) {
        String credentials = user + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
