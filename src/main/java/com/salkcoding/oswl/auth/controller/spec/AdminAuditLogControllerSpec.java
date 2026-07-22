package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Admin — Audit Logs", description = "Instance-wide audit trail. All endpoints require the SYSTEM_ADMIN role.")
public interface AdminAuditLogControllerSpec {

    @Operation(summary = "List audit log entries (paged)",
        description = """
            Returns audit log entries matching the optional filter (date range, actor email, action prefix).
            The page size is capped server-side (default maximum 200).
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Paged audit log entries"),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    Page<AuditLogDto> list(
        @ModelAttribute AuditLogFilter filter,
        @Parameter(description = "Zero-based page index", example = "0")
        @RequestParam(defaultValue = "0") int page,
        @Parameter(description = "Page size (capped server-side)", example = "50")
        @RequestParam(defaultValue = "50") int size
    );

    @Operation(summary = "Export audit logs (CSV)",
        description = "Downloads all audit log entries matching the optional filter as a CSV file.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "CSV export", content = @Content(mediaType = "text/csv")),
        @ApiResponse(responseCode = "403", description = "Caller is not a SYSTEM_ADMIN", content = @Content)
    })
    ResponseEntity<byte[]> exportCsv(
        @ModelAttribute AuditLogFilter filter
    );
}
