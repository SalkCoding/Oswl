package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.AuditLogSiemExportService;
import com.salkcoding.oswl.controller.spec.AdminAuditExportControllerSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Locale;

/**
 * Admin-only audit log export for SIEM ingestion.
 * Shares the {@code /api/admin/audit-logs} prefix with {@code AdminAuditLogController}.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AdminAuditExportController implements AdminAuditExportControllerSpec {

    private final AuditLogSiemExportService siemExportService;
    private final AuditLogService auditLogService;

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@ModelAttribute AuditLogFilter filter,
                                                        @RequestParam(defaultValue = "jsonl") String format) {
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (!siemExportService.isSupported(normalized)) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        auditLogService.log("AUDIT_LOG.EXPORT", "AUDIT_LOG", null, null, describe(normalized, filter));
        StreamingResponseBody body = out -> siemExportService.streamExport(filter, normalized, out);
        MediaType contentType = AuditLogSiemExportService.FORMAT_CEF.equals(normalized)
                ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_NDJSON;
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"audit-logs." + normalized + "\"")
                .body(body);
    }

    private String describe(String format, AuditLogFilter f) {
        StringBuilder sb = new StringBuilder("format=").append(format);
        if (f.getStartDate() != null) sb.append("; startDate=").append(f.getStartDate());
        if (f.getEndDate() != null) sb.append("; endDate=").append(f.getEndDate());
        if (f.getActorEmail() != null && !f.getActorEmail().isBlank()) sb.append("; actorEmail=").append(f.getActorEmail());
        if (f.getAction() != null && !f.getAction().isBlank()) sb.append("; action=").append(f.getAction());
        return sb.toString();
    }
}
