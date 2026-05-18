package com.salkcoding.oswl.dto;

/**
 * A single repository entry returned by the Quick Import repo browser API.
 * Covers GitHub, GitLab, and Bitbucket (cloud + self-hosted).
 */
public record QuickImportRepoDto(
        /** Short repository name (e.g. "my-app") */
        String name,
        /** Full path in owner/repo format (e.g. "octocat/my-app") */
        String fullName,
        /** Browse URL that can be pasted directly into the Quick Import URL field */
        String webUrl,
        /** Default branch name */
        String defaultBranch,
        /** Whether the repository is private */
        boolean isPrivate,
        /** ISO-8601 timestamp of the last update / activity */
        String updatedAt
) {}
