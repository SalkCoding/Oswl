package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.LicenseRowDto;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.ComponentRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LicenseService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ComponentRepository componentRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long scanId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        // Build version history for the dropdown
        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

        // Resolve which scan to display
        ScanResult scan;
        if (scanId != null) {
            scan = allScans.stream()
                    .filter(s -> s.getId().equals(scanId))
                    .findFirst()
                    .orElse(allScans.isEmpty() ? null : allScans.get(0));
        } else {
            scan = allScans.isEmpty() ? null : allScans.get(0);
        }

        Long activeScanId = scan != null ? scan.getId() : null;

        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion() : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(s.getId().equals(activeScanId))
                        .build())
                .toList();

        model.addAttribute("scanVersions", scanVersions);
        model.addAttribute("currentScanId", activeScanId);

        if (scan == null) {
            model.addAttribute("projectVersion", "-");
            model.addAttribute("totalLicenses", 0);
            model.addAttribute("criticalRiskCount", 0);
            model.addAttribute("highRiskCount", 0);
            model.addAttribute("mediumRiskCount", 0);
            model.addAttribute("lowRiskCount", 0);
            model.addAttribute("totalObligations", 0);
            model.addAttribute("licenses", List.of());
            return;
        }

        model.addAttribute("projectVersion", scan.getVersion() != null ? scan.getVersion() : "-");

        List<OswlComponent> components = componentRepository.findByScanResultId(scan.getId());

        // Group by license name
        Map<String, List<OswlComponent>> byLicense = components.stream()
                .filter(c -> c.getLicenseName() != null && !c.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(OswlComponent::getLicenseName));

        List<LicenseRowDto> licenses = byLicense.entrySet().stream()
                .map(entry -> LicenseRowDto.builder()
                        .name(entry.getKey())
                        .riskLevel(computeMaxRisk(entry.getValue()))
                        .libraryCount(entry.getValue().size())
                        .build())
                .sorted(Comparator.comparingInt(dto -> riskOrdinal(dto.getRiskLevel())))
                .collect(Collectors.toList());

        long criticalCount = licenses.stream().filter(l -> "CRITICAL".equals(l.getRiskLevel())).count();
        long highCount     = licenses.stream().filter(l -> "HIGH".equals(l.getRiskLevel())).count();
        long mediumCount   = licenses.stream().filter(l -> "MEDIUM".equals(l.getRiskLevel())).count();
        long lowCount      = licenses.stream().filter(l -> "LOW".equals(l.getRiskLevel())).count();

        // VIOLATION + WARN component count = components with obligations
        long totalObligations = components.stream()
                .filter(c -> c.getLicenseStatus() != LicenseStatus.OK)
                .count();

        model.addAttribute("totalLicenses", licenses.size());
        model.addAttribute("criticalRiskCount", criticalCount);
        model.addAttribute("highRiskCount", highCount);
        model.addAttribute("mediumRiskCount", mediumCount);
        model.addAttribute("lowRiskCount", lowCount);
        model.addAttribute("totalObligations", totalObligations);
        model.addAttribute("licenses", licenses);
    }

    private String computeMaxRisk(List<OswlComponent> comps) {
        boolean hasViolation = comps.stream()
                .anyMatch(c -> c.getLicenseStatus() == LicenseStatus.VIOLATION);
        if (hasViolation) return "CRITICAL";
        boolean hasWarn = comps.stream()
                .anyMatch(c -> c.getLicenseStatus() == LicenseStatus.WARN);
        if (hasWarn) return "HIGH";
        return "LOW";
    }

    private int riskOrdinal(String risk) {
        return switch (risk) {
            case "CRITICAL" -> 0;
            case "HIGH"     -> 1;
            case "MEDIUM"   -> 2;
            default         -> 3;
        };
    }
}
