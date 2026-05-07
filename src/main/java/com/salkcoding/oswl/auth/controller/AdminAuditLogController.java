package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.AuditLogDto;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    public Page<AuditLogDto> list(@ModelAttribute AuditLogFilter filter,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size) {
        return auditLogService.findAll(filter, PageRequest.of(page, Math.min(size, 200)));
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
