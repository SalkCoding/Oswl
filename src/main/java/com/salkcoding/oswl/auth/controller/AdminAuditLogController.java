package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.controller.spec.AdminAuditLogControllerSpec;
import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Reading the audit log is delegatable via {@code AUDIT_LOG_VIEW}. It used to be
 * SYSTEM_ADMIN-only, which made that permission grantable but inert — its sibling
 * {@code AdminAuditExportController} already honoured {@code AUDIT_LOG_EXPORT}, so the same
 * role template could export the log but not open it.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'AUDIT_LOG_VIEW') or hasRole('SYSTEM_ADMIN')")
public class AdminAuditLogController implements AdminAuditLogControllerSpec {

    private final AuditLogService auditLogService;

    @Value("${oswl.audit.max-page-size:200}")
    private int maxPageSize;

    @GetMapping
    public Page<AuditLogDto> list(@ModelAttribute AuditLogFilter filter,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return auditLogService.findAll(filter, PageRequest.of(page, Math.min(size, maxPageSize)));
    }

    @GetMapping(value = "/export.csv")
    public ResponseEntity<byte[]> exportCsv(@ModelAttribute AuditLogFilter filter) {
        byte[] body = auditLogService.exportCsv(filter);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", "audit-logs.csv");
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
