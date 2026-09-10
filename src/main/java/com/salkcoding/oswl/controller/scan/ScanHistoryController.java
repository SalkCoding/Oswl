package com.salkcoding.oswl.controller.scan;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.controller.spec.ScanHistoryControllerSpec;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import com.salkcoding.oswl.service.scan.ScanHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/projects/{projectId}/scan-history")
@PreAuthorize("hasPermission(null, 'SCAN_HISTORY_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ScanHistoryController implements ScanHistoryControllerSpec {

    private final ScanHistoryService scanHistoryService;
    private final ScanResultRepository scanResultRepository;
    private final AuditLogService auditLogService;
    private final ProjectAccessService projectAccessService;
    private final AirgappedSnapshotService airgappedSnapshotService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        projectAccessService.assertCanViewProject(projectId);
        scanHistoryService.populateModel(projectId, model);
        addDefinitionsAsOf(model);
        return "scan-history/index";
    }

    /**
     * In air-gapped mode, the scan results were analyzed against snapshot definitions as of
     * {@link AirgappedSnapshotService#oldestSourceAsOf()}; the page shows that date so auditors can
     * see how fresh the underlying data was. Null outside air-gapped mode or with no provenance.
     */
    private void addDefinitionsAsOf(Model model) {
        if (airgapped) {
            java.time.LocalDate asOf = airgappedSnapshotService.oldestSourceAsOf();
            if (asOf != null) {
                model.addAttribute("airgappedDefinitionsAsOf", asOf.toString());
            }
        }
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
