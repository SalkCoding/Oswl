package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubImportRequest;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Tag(name = "GitHub Integration", description = "Session-scoped GitHub PAT management and repository discovery for the Git Integration UI panel.")
public interface GitHubApiControllerSpec {

    @Operation(
        summary = "Connect a GitHub PAT",
        description = """
            Validates the supplied GitHub Personal Access Token and stores it in the HTTP session.
            Multiple accounts are supported — each user login maps to its own token.
            
            Requires the token to have at least `repo` and `read:org` scopes.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token accepted",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"connected\": true, \"login\": \"octocat\" }"))),
        @ApiResponse(responseCode = "400", description = "Token field missing", content = @Content),
        @ApiResponse(responseCode = "401", description = "Invalid or expired GitHub token", content = @Content)
    })
    @RequestBody(
        description = "GitHub PAT payload",
        required = true,
        content = @Content(
            schema = @Schema(type = "object"),
            examples = @ExampleObject(value = "{ \"token\": \"ghp_xxxxxxxxxxxx\" }")
        )
    )
    ResponseEntity<Map<String, Object>> connect(
        @RequestBody Map<String, String> body,
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "Disconnect all GitHub accounts",
        description = "Removes all stored GitHub tokens from the current HTTP session."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "All accounts disconnected",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"disconnected\": true }")))
    })
    ResponseEntity<Map<String, Object>> disconnect(
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "Disconnect a single GitHub account",
        description = "Removes the token for the specified GitHub user login from the current HTTP session."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account removed",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"removed\": \"octocat\" }")))
    })
    ResponseEntity<Map<String, Object>> disconnectAccount(
        @Parameter(description = "GitHub user login to remove", example = "octocat", required = true)
        @PathVariable String login,
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "GitHub connection status",
        description = "Returns whether at least one valid GitHub token is stored in the current session."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status returned",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = {
                    @ExampleObject(name = "connected",    value = "{ \"connected\": true, \"login\": \"octocat\" }"),
                    @ExampleObject(name = "disconnected", value = "{ \"connected\": false }")
                }))
    })
    ResponseEntity<Map<String, Object>> status(
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "List connected GitHub accounts",
        description = """
            Returns all GitHub accounts (users and organizations) reachable from stored session tokens.
            De-duplicates entries across multiple connected PATs.
            Returns `401` when no tokens are stored.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account list returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = GitHubAccountDto.class)),
                examples = @ExampleObject(value = """
                    [
                      { "login": "octocat", "type": "User", "avatarUrl": "https://avatars.githubusercontent.com/u/583231" },
                      { "login": "github", "type": "Organization", "avatarUrl": "https://avatars.githubusercontent.com/u/9919" }
                    ]
                    """))),
        @ApiResponse(responseCode = "401", description = "No connected GitHub tokens", content = @Content)
    })
    ResponseEntity<List<GitHubAccountDto>> accounts(
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "List repositories for an account",
        description = "Returns all repositories accessible to the specified account login. Returns `401` when no token is available for that account."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Repository list returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = GitHubRepoDto.class)))),
        @ApiResponse(responseCode = "401", description = "No token for the given account", content = @Content)
    })
    ResponseEntity<List<GitHubRepoDto>> repos(
        @Parameter(description = "GitHub account login (user or org)", example = "octocat", required = true)
        @RequestParam String account,
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "List branches for a repository",
        description = "Returns branch names for the given `owner/repo`. Returns `401` when no token is available for the owner."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Branch list returned",
            content = @Content(
                array = @ArraySchema(schema = @Schema(type = "string")),
                examples = @ExampleObject(value = "[\"main\", \"develop\", \"feature/scanner\"]"))),
        @ApiResponse(responseCode = "401", description = "No token for the given owner", content = @Content)
    })
    ResponseEntity<List<String>> branches(
        @Parameter(description = "Repository owner (user or org login)", example = "octocat", required = true)
        @RequestParam String owner,
        @Parameter(description = "Repository name", example = "Hello-World", required = true)
        @RequestParam String repo,
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "Get last commit date for a branch",
        description = "Returns the ISO-8601 date of the most recent commit on the specified branch."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Last commit date returned",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = "{ \"updatedAt\": \"2026-05-01T14:23:00Z\" }"))),
        @ApiResponse(responseCode = "401", description = "No token for the given owner", content = @Content)
    })
    ResponseEntity<Map<String, String>> branchUpdatedAt(
        @Parameter(description = "Repository owner", example = "octocat", required = true)
        @RequestParam String owner,
        @Parameter(description = "Repository name", example = "Hello-World", required = true)
        @RequestParam String repo,
        @Parameter(description = "Branch name", example = "main", required = true)
        @RequestParam String branch,
        @Parameter(hidden = true) HttpSession session
    );

    @Operation(
        summary = "Import a GitHub repository as a project",
        description = """
            Creates or updates an OsWL project from the specified GitHub repository.
            If a project with the same repository already exists it is updated; otherwise a new project is created.
            Returns `401` when no token is available for the repository owner.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Project created or updated",
            content = @Content(
                schema = @Schema(type = "object"),
                examples = @ExampleObject(value = """
                    {
                      "projectId": 5,
                      "projectName": "Hello-World",
                      "projectUuid": "550e8400-e29b-41d4-a716-446655440000"
                    }
                    """))),
        @ApiResponse(responseCode = "401", description = "No token for the repository owner", content = @Content)
    })
    @RequestBody(
        description = "Repository import payload",
        required = true,
        content = @Content(
            schema = @Schema(implementation = GitHubImportRequest.class),
            examples = @ExampleObject(value = """
                { "owner": "octocat", "repo": "Hello-World", "branch": "main" }
                """)
        )
    )
    ResponseEntity<Map<String, Object>> importRepo(
        @RequestBody GitHubImportRequest request,
        @Parameter(hidden = true) HttpSession session
    );
}
