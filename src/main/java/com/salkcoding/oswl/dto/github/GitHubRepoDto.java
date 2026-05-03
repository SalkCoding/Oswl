package com.salkcoding.oswl.dto.github;

import lombok.Builder;
import lombok.Getter;

/**
 * A single GitHub repository entry returned to the frontend.
 */
@Getter
@Builder
public class GitHubRepoDto {
    private long id;
    private String name;
    private String fullName;
    private String defaultBranch;
    private String updatedAt;
    private boolean isPrivate;
}
