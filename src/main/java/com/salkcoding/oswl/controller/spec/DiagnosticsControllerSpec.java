package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.diagnostics.DiagnosticCheckResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;

@Tag(name = "Settings — Diagnostics", description = "Self-service connectivity/health checks. SYSTEM_ADMIN only.")
public interface DiagnosticsControllerSpec {

    @Operation(summary = "Run self-diagnostics",
        description = """
            Runs every diagnostic check synchronously and returns the results: database, disk
            space, embedded AI sidecar, air-gapped snapshot freshness, active AI provider
            reachability, SMTP, the current user's VCS token validity, and outbound reachability
            of OSV / deps.dev / FIRST.org EPSS / CISA KEV. Each result is safe to paste into a
            support request — no secret, token, or password value is ever included.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Diagnostic results",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = DiagnosticCheckResult.class)))),
        @ApiResponse(responseCode = "403", description = "Not a SYSTEM_ADMIN", content = @Content)
    })
    List<DiagnosticCheckResult> run(@Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal);
}
