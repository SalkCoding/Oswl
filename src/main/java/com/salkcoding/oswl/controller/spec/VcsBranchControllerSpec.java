package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Tag(name = "VCS", description = "Provider-agnostic VCS helpers. Used by the web UI to populate branch selectors.")
public interface VcsBranchControllerSpec {

    @Operation(summary = "List branches for a project's VCS repository",
        description = """
            Returns the branch list from the project's linked VCS repository (GitHub, GitLab, or Bitbucket).
            Uses the authenticated user's stored access token for the relevant provider.
            Falls back to `[\"main\"]` if no token is found or the project has no VCS link.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Branch list",
            content = @Content(schema = @Schema(example = "[\"main\", \"develop\", \"feature/cve-scan\"]")))
    })
    ResponseEntity<List<String>> branches(
        @Parameter(description = "Project ID", example = "1", required = true) @RequestParam Long projectId,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal,
        @Parameter(hidden = true) HttpSession session
    );
}
