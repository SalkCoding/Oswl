package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ScanHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/projects/{projectId}/scan-history")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SCAN_HISTORY_VIEW') or hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class ScanHistoryController {

    private final ScanHistoryService scanHistoryService;
    private final ScanResultRepository scanResultRepository;

    @GetMapping
    public String index(@PathVariable Long projectId, Model model) {
        scanHistoryService.populateModel(projectId, model);
        return "scan-history/index";
    }

    @DeleteMapping("/{scanId}")
    @ResponseBody
    public ResponseEntity<Void> deleteScan(
            @PathVariable Long projectId,
            @PathVariable Long scanId) {
        scanResultRepository.findByIdAndProjectId(scanId, projectId)
                .ifPresent(scanResultRepository::delete);
        return ResponseEntity.noContent().build();
    }
}
