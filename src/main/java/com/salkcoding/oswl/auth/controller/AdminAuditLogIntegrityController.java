package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.controller.spec.AdminAuditLogIntegrityControllerSpec;
import com.salkcoding.oswl.auth.dto.AuditLogFilter;
import com.salkcoding.oswl.auth.dto.AuditLogIntegrityReport;
import com.salkcoding.oswl.auth.service.AuditLogIntegrityService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit-logs/integrity")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'AUDIT_LOG_VERIFY') or hasRole('SYSTEM_ADMIN')")
public class AdminAuditLogIntegrityController implements AdminAuditLogIntegrityControllerSpec {

    private final AuditLogIntegrityService integrityService;

    @GetMapping("/verify")
    public AuditLogIntegrityReport verify(@ModelAttribute AuditLogFilter filter,
                                          @RequestParam(defaultValue = "1000") int batchSize) {
        return integrityService.verify(filter, batchSize);
    }
}
