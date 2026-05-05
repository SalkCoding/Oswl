package com.salkcoding.oswl.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * A single GitHub repository entry returned to the frontend.
 */
@Schema(description = "GitHub repository available for import")
@Getter
@Builder
public class GitHubRepoDto {

    @Schema(description = "GitHub repository ID", example = "1296269")
    private long id;

    @Schema(description = "Repository name", example = "Hello-World")
    private String name;

    @Schema(description = "Full repository name in owner/repo format", example = "octocat/Hello-World")
    private String fullName;

    @Schema(description = "Default branch name", example = "main")
    private String defaultBranch;

    @Schema(description = "ISO-8601 date of the last push", example = "2026-05-01T14:23:00Z")
    private String updatedAt;

    @Schema(description = "Whether the repository is private", example = "false")
    private boolean isPrivate;
}
