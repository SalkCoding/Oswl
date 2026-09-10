package com.salkcoding.oswl.auth.controller.spec;

import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.dto.AuditLogIntegrityReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "Admin — Audit Log Integrity", description = "Hash-chain verification of the audit trail. Requires the AUDIT_LOG_VERIFY permission.")
public interface AdminAuditLogIntegrityControllerSpec {

    @Operation(summary = "Verify the audit log integrity chain",
        description = """
            Recomputes the SHA-256 integrity hash for each audit log entry and checks that every
            hashed row references the hash of the immediately preceding row. Rows without a hash
            are treated as legacy entries and are skipped until the first hashed row is reached.
            The verification itself is recorded as AUDIT_LOG.VERIFY.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Integrity verification report"),
        @ApiResponse(responseCode = "403", description = "Caller lacks AUDIT_LOG_VERIFY", content = @Content)
    })
    AuditLogIntegrityReport verify(
        @ModelAttribute AuditLogFilter filter,
        @Parameter(description = "Number of rows to verify in each batch", example = "1000")
        @RequestParam(defaultValue = "1000") int batchSize
    );
}
