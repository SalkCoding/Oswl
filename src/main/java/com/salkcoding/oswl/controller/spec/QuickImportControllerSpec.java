package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.QuickImportRequest;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

@Tag(name = "Quick Import", description = "Async repository import from GitHub, GitLab, or Bitbucket.")
public interface QuickImportControllerSpec {

    @Hidden
    String quickImportPage();

    @Operation(summary = "List the current user's VCS connections",
        description = "Returns active VCS connections for the authenticated user, used to populate the provider selector in the Quick Import UI.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "VCS connection list",
            content = @Content(schema = @Schema(implementation = VcsConnectionDto.class)))
    })
    ResponseEntity<List<VcsConnectionDto>> connections(
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "List repositories for a VCS provider",
        description = "Returns repositories accessible by the current user for the given provider. Fetches from the upstream API in real time.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Repository list",
            content = @Content(schema = @Schema(implementation = QuickImportRepoDto.class))),
        @ApiResponse(responseCode = "502", description = "Upstream VCS API error",
            content = @Content(schema = @Schema(example = "{\"error\": \"Bad credentials\"}")))
    })
    ResponseEntity<?> listRepos(
        @Parameter(description = "VCS provider", schema = @Schema(implementation = VcsProvider.class), required = true)
        @RequestParam VcsProvider provider,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Start an async Quick Import job",
        description = "Kicks off an async clone-and-scan job. Returns immediately with a `jobId` that can be polled via `GET /api/quick-import/job/{jobId}`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job started",
            content = @Content(schema = @Schema(example = "{\"jobId\": \"a1b2c3d4-...\"}"))),
        @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
    })
    ResponseEntity<Map<String, String>> start(
        @Valid @RequestBody QuickImportRequest request,
        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Poll Quick Import job status",
        description = "Returns the current state of an import job. Poll until `status` is `COMPLETED` or `FAILED`.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Job status",
            content = @Content(schema = @Schema(implementation = QuickImportJobStatus.class))),
        @ApiResponse(responseCode = "404", description = "Unknown or expired job ID", content = @Content)
    })
    ResponseEntity<QuickImportJobStatus> jobStatus(
        @Parameter(description = "Job ID returned by the start endpoint", required = true) @PathVariable String jobId
    );
}
