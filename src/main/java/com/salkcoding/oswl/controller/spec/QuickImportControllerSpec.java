package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobsResponse;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.QuickImportRequest;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "Quick Import", description = """
        Async repository import from GitHub, GitLab, or Bitbucket.
        Up to three imports run concurrently (instance-wide); additional jobs are queued FIFO.
        Each user may have at most three queued jobs; exceeding that returns HTTP 429.
        Progress is available via polling or Server-Sent Events (SSE).
        """)
public interface QuickImportControllerSpec {

    @Hidden
    String quickImportPage();

    @Operation(summary = "List the current user's VCS connections",
            description = "Returns active VCS connections for the authenticated user.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "VCS connection list",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = VcsConnectionDto.class))))
    })
    ResponseEntity<List<VcsConnectionDto>> connections(
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "List repositories for a VCS provider",
            description = "Returns repositories accessible by the current user. Fetches from the upstream API in real time.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Repository list",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = QuickImportRepoDto.class)))),
            @ApiResponse(responseCode = "400", description = "Blocked outbound URL", content = @Content),
            @ApiResponse(responseCode = "502", description = "Upstream VCS API error",
                    content = @Content(schema = @Schema(example = "{\"error\": \"Bad credentials\"}")))
    })
    ResponseEntity<?> listRepos(
            @Parameter(description = "VCS provider", required = true) @RequestParam VcsProvider provider,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Start an async Quick Import job",
            description = """
                    Enqueues a clone-and-scan job and returns immediately with a `jobId`.
                    Each call creates a **new** job (multiple imports per user are supported).
                    Poll `GET /api/quick-import/job/{jobId}` or subscribe to SSE on `/stream`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job enqueued",
                    content = @Content(schema = @Schema(example = "{\"jobId\": \"a1b2c3d4-e5f6-7890-abcd-ef1234567890\"}"))),
            @ApiResponse(responseCode = "400", description = "Validation error", content = @Content),
            @ApiResponse(responseCode = "429", description = "Per-user queued job limit reached",
                    content = @Content(schema = @Schema(example = "{\"error\": \"You can queue up to 3 imports.\"}")))
    })
    ResponseEntity<?> start(
            @Valid @RequestBody QuickImportRequest request,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "List Quick Import jobs for the current user",
            description = """
                    Returns all in-memory jobs owned by the user plus queue capacity snapshot
                    (`activeSlotsUsed`, `userQueuedCount`, `userRunningCount`).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job list with queue snapshot",
                    content = @Content(schema = @Schema(implementation = QuickImportJobsResponse.class)))
    })
    ResponseEntity<QuickImportJobsResponse> listJobs(
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Poll Quick Import job status",
            description = """
                    Returns the current snapshot for one job.
                    Poll until `phase` is `DONE` or `FAILED`.
                    During `ENRICHING`, `percent`, `subPhase`, `detailLines`, and `aiPreviews` are populated.
                    The API token is revealed in full only on the first `DONE` response.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job status",
                    content = @Content(schema = @Schema(implementation = QuickImportJobStatus.class))),
            @ApiResponse(responseCode = "404", description = "Unknown job or not owned by user", content = @Content)
    })
    ResponseEntity<QuickImportJobStatus> jobStatus(
            @Parameter(description = "Job ID from POST /api/quick-import/start", required = true)
            @PathVariable String jobId,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );

    @Operation(summary = "Subscribe to Quick Import job updates (SSE)",
            description = """
                    Server-Sent Events stream. Each event is named `job-update` with a JSON `QuickImportJobStatus` body.
                    The server sends the current status immediately on connect, then on each state change.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE stream (`text/event-stream`)",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE))
    })
    SseEmitter jobStream(
            @Parameter(description = "Job ID", required = true) @PathVariable String jobId,
            @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal
    );
}
