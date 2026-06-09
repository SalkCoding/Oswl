package com.salkcoding.oswl.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /api/github/repos/import — creates a Project from a GitHub repo.
 */
@Schema(description = "Repository import request — creates or updates an OsWL project from a GitHub repository")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GitHubImportRequest {

    @Schema(description = "Repository owner login (user or organization)", example = "octocat", requiredMode = Schema.RequiredMode.REQUIRED)
    private String owner;

    @Schema(description = "Repository name", example = "Hello-World", requiredMode = Schema.RequiredMode.REQUIRED)
    private String repo;

    @Schema(description = "Branch to track (defaults to the repository default branch if omitted)", example = "main")
    private String branch;
}
