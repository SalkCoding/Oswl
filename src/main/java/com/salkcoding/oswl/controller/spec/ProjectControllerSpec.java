package com.salkcoding.oswl.controller.spec;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Tag(name = "Projects", description = "Project management and Git/VCS helper endpoints used by the web UI.")
public interface ProjectControllerSpec {

    @Operation(
        summary = "Delete a project",
        description = "Permanently removes the project and all associated scan results, components, CVEs, and API keys."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Project deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<Void> deleteProject(
        @Parameter(description = "Project ID to delete", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "List available Git branches",
        description = "Returns a sample list of Git branches for the branch selector in the Git integration UI. Will be replaced with a real VCS API call in a future release."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Branch list",
            content = @Content(
                array = @ArraySchema(schema = @Schema(type = "string")),
                examples = @ExampleObject(value = """
                    ["main", "develop", "feature/new-feature", "hotfix/bug-fix", "release/v1.0.0", "staging"]
                    """)))
    })
    ResponseEntity<List<String>> getBranches();

    @Operation(
        summary = "List available Git accounts",
        description = "Returns a sample list of VCS account names for the account selector in the Git integration UI."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account list",
            content = @Content(
                array = @ArraySchema(schema = @Schema(type = "string")),
                examples = @ExampleObject(value = """
                    ["OwlCoding", "OWL-Team", "OWL-Analytics", "OWL-Security"]
                    """)))
    })
    ResponseEntity<List<String>> getAccounts();
}
