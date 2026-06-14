package com.salkcoding.oswl.dto.github;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Represents a GitHub account (user or organization) available for repo import.
 */
@Schema(description = "GitHub account (user or organization) returned by the accounts endpoint")
@Getter
@Builder
public class GitHubAccountDto {

    @Schema(description = "GitHub user or organization login", example = "octocat")
    private String login;

    @Schema(description = "Account type", example = "User", allowableValues = {"User", "Organization"})
    private String type;

    @Schema(description = "URL of the account avatar image", example = "https://avatars.githubusercontent.com/u/583231")
    private String avatarUrl;
}
