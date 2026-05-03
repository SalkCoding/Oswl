package com.salkcoding.oswl.dto.github;

import lombok.Builder;
import lombok.Getter;

/**
 * Represents a GitHub account (user or organization) available for repo import.
 */
@Getter
@Builder
public class GitHubAccountDto {
    private String login;
    /** "User" or "Organization" */
    private String type;
    private String avatarUrl;
}
