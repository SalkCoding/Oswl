package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Tag(name = "Admin — Audit Log Export", description = "SIEM-oriented audit log export. All endpoints require the SYSTEM_ADMIN role.")
public interface AdminAuditExportControllerSpec {

    @Operation(summary = "Export audit logs for SIEM ingestion",
        description = """
            Streams all audit log entries matching the optional filter (date range, actor email, action),
            ordered newest first, as a downloadable file.
            - `format=jsonl` (default): JSON Lines — one JSON object per line, UTF-8.
            - `format=cef`: ArcSight Common Event Format (`CEF:0|SalkCoding|OsWL|1.0|...`).
            The export itself is recorded in the audit log as `AUDIT_LOG.EXPORT`.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Audit log export stream",
            content = @Content(mediaType = "application/x-ndjson")),
        @ApiResponse(responseCode = "400", description = "Unsupported `format` value", content = @Content),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    ResponseEntity<StreamingResponseBody> export(
        @ModelAttribute AuditLogFilter filter,
        @Parameter(description = "Export format", example = "jsonl",
            schema = @Schema(allowableValues = {"jsonl", "cef"}, defaultValue = "jsonl"))
        @RequestParam(defaultValue = "jsonl") String format
    );
}
