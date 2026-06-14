package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ScanHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/projects/{projectId}/scan-history")
@PreAuthorize("hasPermission(null, 'SCAN_HISTORY_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ScanHistoryController {

    private final ScanHistoryService scanHistoryService;
    private final ScanResultRepository scanResultRepository;
    private final AuditLogService auditLogService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        projectAccessService.assertCanViewProject(projectId);
        scanHistoryService.populateModel(projectId, model);
        return "scan-history/index";
    }

    @DeleteMapping("/{scanId}")
    @ResponseBody
    @PreAuthorize("hasPermission(null, 'SCAN_HISTORY_DELETE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteScan(
            @PathVariable Long projectId,
            @PathVariable Long scanId) {
        projectAccessService.assertCanViewProject(projectId);
        scanResultRepository.findByIdAndProjectId(scanId, projectId).ifPresent(scan -> {
            String version = scan.getVersion() != null ? scan.getVersion() : "-";
            scanResultRepository.delete(scan);
            auditLogService.log("SCAN.DELETE", "SCAN_RESULT",
                    scanId.toString(), version,
                    "projectId=" + projectId);
        });
        return ResponseEntity.noContent().build();
    }
}
