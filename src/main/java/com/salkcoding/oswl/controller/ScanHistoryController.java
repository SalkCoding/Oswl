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
        scanResultRepository.findById(scanId).ifPresent(scan -> {
            if (!scan.getProject().getId().equals(projectId)) {
                throw new IllegalArgumentException("Scan does not belong to project");
            }
            scanResultRepository.deleteById(scanId);
        });
        return ResponseEntity.noContent().build();
    }
}
