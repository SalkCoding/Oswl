package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.controller.ProjectController.CreateProjectRequest;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "Projects", description = "Project management and Git/VCS helper endpoints used by the web UI.")
public interface ProjectControllerSpec {

    @Hidden
    String index(Model model);

    @Operation(
        summary = "List projects (JSON)",
        description = "Returns every project as a summary card including security/license figures from the latest completed scan. Requires PROJECT_VIEW permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Project list returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProjectSummaryDto.class))))
    })
    ResponseEntity<List<ProjectSummaryDto>> listJson();

    @Operation(
        summary = "Subscribe to project scan status updates (SSE)",
        description = """
            Server-Sent Events stream (`text/event-stream`). The server pushes an event the moment
            the scan of one of the watched projects reaches a terminal state (COMPLETED or FAILED).
            Used by the projects dashboard to refresh the project cards grid without polling.
            Requires PROJECT_VIEW permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SSE stream (`text/event-stream`)",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    })
    SseEmitter scanStatusStream(
        @Parameter(description = "Project IDs to watch", example = "[1, 2, 3]", required = true)
        @RequestParam List<Long> ids
    );

    @Hidden
    String projectCardsFragment(Model model);

    @Operation(
        summary = "Create a blank project",
        description = "Creates a new project without a VCS link. Requires PROJECT_CREATE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Project created",
            content = @Content(examples = @ExampleObject(value = "{ \"id\": 1, \"name\": \"My-Service\" }"))),
        @ApiResponse(responseCode = "400", description = "Validation error — name is required and must not exceed 200 characters", content = @Content)
    })
    ResponseEntity<Map<String, Object>> createProject(
        @Valid @RequestBody CreateProjectRequest req
    );

    @Operation(
        summary = "Delete a project (move to trash)",
        description = """
            Soft-deletes the project: it is moved to the trash and can be restored later with
            `POST /projects/{projectId}/restore`. Scan results, components, CVEs, and API keys are kept.
            Use `DELETE /projects/{projectId}/permanent` for irreversible removal.
            Requires PROJECT_DELETE permission.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Project moved to trash", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found", content = @Content)
    })
    ResponseEntity<Void> deleteProject(
        @Parameter(description = "Project ID to delete", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "Restore a trashed project",
        description = "Restores a soft-deleted project from the trash. Requires PROJECT_RESTORE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Project restored", content = @Content),
        @ApiResponse(responseCode = "404", description = "Project not found in trash", content = @Content)
    })
    ResponseEntity<Void> restoreProject(
        @Parameter(description = "Project ID to restore", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "Permanently delete a trashed project",
        description = "Irreversibly removes a trashed project and all associated scan results, components, CVEs, and API keys. Requires PROJECT_PERMANENT_DELETE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Project permanently deleted", content = @Content)
    })
    ResponseEntity<Void> permanentDeleteProject(
        @Parameter(description = "Project ID to permanently delete", example = "1", required = true)
        @PathVariable Long projectId
    );

    @Operation(
        summary = "Permanently delete all trashed projects",
        description = "Irreversibly removes every project currently in the trash. Requires PROJECT_PERMANENT_DELETE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Trash emptied", content = @Content)
    })
    ResponseEntity<Void> permanentDeleteAll();

    @Operation(
        summary = "Permanently delete selected trashed projects",
        description = "Irreversibly removes the given trashed projects. Requires PROJECT_PERMANENT_DELETE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Selected projects permanently deleted", content = @Content)
    })
    ResponseEntity<Void> permanentDeleteSelected(
        @RequestBody List<Long> ids
    );

    @Operation(
        summary = "Restore selected trashed projects",
        description = "Restores the given projects from the trash. Requires PROJECT_RESTORE permission."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Selected projects restored", content = @Content)
    })
    ResponseEntity<Void> restoreSelected(
        @RequestBody List<Long> ids
    );

    @Hidden
    String cliIntegration();

    @Hidden
    String gitIntegration();
}
