package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionDiffRowDto.ChangeType;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VersionDiffService {

    private final ProjectRepository       projectRepository;
    private final ScanResultRepository    scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long fromScanId, Long toScanId, Model model) {
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId",   projectId);
        model.addAttribute("projectName", project.getName());

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

        // Build scan version list for dropdowns
        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion()
                                : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null
                                ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(false)
                        .build())
                .toList();
        model.addAttribute("scanVersions", scanVersions);

        // Resolve from/to scans — default: from = second latest, to = latest
        ScanResult fromScan = null;
        ScanResult toScan   = null;

        if (fromScanId != null) {
            fromScan = allScans.stream().filter(s -> s.getId().equals(fromScanId)).findFirst().orElse(null);
        }
        if (toScanId != null) {
            toScan = allScans.stream().filter(s -> s.getId().equals(toScanId)).findFirst().orElse(null);
        }

        // Sensible defaults when params missing
        if (toScan == null && !allScans.isEmpty())   toScan   = allScans.get(0);
        if (fromScan == null && allScans.size() > 1) fromScan = allScans.get(1);
        if (fromScan == null)                        fromScan = toScan;

        String fromVersion = fromScan != null ? versionLabel(fromScan) : "-";
        String toVersion   = toScan   != null ? versionLabel(toScan)   : "-";

        model.addAttribute("fromScanId",   fromScan != null ? fromScan.getId() : null);
        model.addAttribute("toScanId",     toScan   != null ? toScan.getId()   : null);
        model.addAttribute("fromVersion",  fromVersion);
        model.addAttribute("toVersion",    toVersion);

        if (fromScan == null || toScan == null || fromScan.getId().equals(toScan.getId())) {
            model.addAttribute("diffRows", List.of());
            model.addAttribute("totalCount",      0);
            model.addAttribute("addedCount",      0);
            model.addAttribute("removedCount",    0);
            model.addAttribute("updatedCount",    0);
            model.addAttribute("newThreatCount",  0);
            return;
        }

        // Build name→component maps (key = library name)
        Map<String, ScanComponent> fromMap = buildMap(fromScan.getId());
        Map<String, ScanComponent> toMap   = buildMap(toScan.getId());

        List<VersionDiffRowDto> rows = new ArrayList<>();
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(fromMap.keySet());
        allNames.addAll(toMap.keySet());

        int added = 0, removed = 0, updated = 0, newThreat = 0;

        for (String name : allNames) {
            ScanComponent fromComp = fromMap.get(name);
            ScanComponent toComp   = toMap.get(name);

            ChangeType type;
            if (fromComp == null) {
                type = ChangeType.ADDED;
                added++;
            } else if (toComp == null) {
                type = ChangeType.REMOVED;
                removed++;
            } else {
                boolean versionChanged = !Objects.equals(fromComp.getVersion(), toComp.getVersion());
                RiskLevel fromRisk = fromComp.getLibrary().highestSeverity();
                RiskLevel toRisk   = toComp.getLibrary().highestSeverity();

                // NEW_THREAT: no risk before → any risk now (LOW or above)
                boolean isNewThreat = fromRisk == RiskLevel.NONE && toRisk != RiskLevel.NONE;

                if (isNewThreat) {
                    type = ChangeType.NEW_THREAT;
                    newThreat++;
                } else if (versionChanged) {
                    type = ChangeType.UPDATED;
                    updated++;
                } else {
                    continue; // skip unchanged
                }
            }

            rows.add(VersionDiffRowDto.builder()
                    .fromName(fromComp != null ? fromComp.getName() : null)
                    .fromVersion(fromComp != null ? fromComp.getVersion() : null)
                    .fromRiskLevel(fromComp != null ? fromComp.getLibrary().highestSeverity().name() : null)
                    .toName(toComp != null ? toComp.getName() : null)
                    .toVersion(toComp != null ? toComp.getVersion() : null)
                    .toRiskLevel(toComp != null ? toComp.getLibrary().highestSeverity().name() : null)
                    .changeType(type)
                    .build());
        }

        model.addAttribute("diffRows",       rows);
        model.addAttribute("totalCount",     rows.size());
        model.addAttribute("addedCount",     added);
        model.addAttribute("removedCount",   removed);
        model.addAttribute("updatedCount",   updated);
        model.addAttribute("newThreatCount", newThreat);
    }

    private Map<String, ScanComponent> buildMap(Long scanId) {
        return scanComponentRepository.findByScanResultId(scanId).stream()
                .collect(Collectors.toMap(
                        sc -> sc.getLibrary().getName(),
                        sc -> sc,
                        (a, b) -> a // keep first on duplicate name
                ));
    }

    private String versionLabel(ScanResult s) {
        return s.getVersion() != null ? s.getVersion()
                : s.getScannedAt().toLocalDate().toString().replace("-", ".");
    }
}
