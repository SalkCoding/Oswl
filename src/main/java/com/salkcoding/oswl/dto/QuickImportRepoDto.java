package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A single repository entry returned by the Quick Import repo browser API.
 * Covers GitHub, GitLab, and Bitbucket (cloud + self-hosted).
 */
@Schema(description = "Repository entry returned by the Quick Import repo browser")
public record QuickImportRepoDto(
        @Schema(description = "Short repository name", example = "my-app")
        /** Short repository name (e.g. "my-app") */
        String name,
        @Schema(description = "Full path in owner/repo format", example = "octocat/my-app")
        /** Full path in owner/repo format (e.g. "octocat/my-app") */
        String fullName,
        @Schema(description = "Browse URL that can be pasted into the Quick Import URL field",
                example = "https://github.com/octocat/my-app")
        /** Browse URL that can be pasted directly into the Quick Import URL field */
        String webUrl,
        @Schema(description = "Default branch name", example = "main")
        /** Default branch name */
        String defaultBranch,
        @Schema(description = "Whether the repository is private", example = "false")
        /** Whether the repository is private */
        boolean isPrivate,
        @Schema(description = "Last update / activity timestamp (ISO-8601)", example = "2026-05-10T14:30:00Z")
        /** ISO-8601 timestamp of the last update / activity */
        String updatedAt
) {}
