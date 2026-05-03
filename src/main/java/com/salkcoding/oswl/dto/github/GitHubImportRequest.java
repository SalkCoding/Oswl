package com.salkcoding.oswl.dto.github;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /api/github/repos/import — creates a Project from a GitHub repo.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GitHubImportRequest {
    private String owner;
    private String repo;
    private String branch;
}
