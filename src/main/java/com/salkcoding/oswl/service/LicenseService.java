package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.LicenseRowDto;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.LibraryRepository;
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

    private final ProjectRepository    projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final LibraryRepository    libraryRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long scanId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

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
                        .version(s.getVersion() != null ? s.getVersion()
                                : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null
                                ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(s.getId().equals(activeScanId))
                        .build())
                .toList();

        model.addAttribute("scanVersions", scanVersions);
        model.addAttribute("currentScanId", activeScanId);

        if (scan == null) {
            model.addAttribute("projectVersion", "-");
            model.addAttribute("totalLicenses", 0);
            model.addAttribute("restrictedCount", 0);
            model.addAttribute("cautionCount", 0);
            model.addAttribute("permittedCount", 0);
            model.addAttribute("unknownCount", 0);
            model.addAttribute("totalObligations", 0);
            model.addAttribute("licenses", List.of());
            return;
        }

        model.addAttribute("projectVersion", scan.getVersion() != null ? scan.getVersion() : "-");

        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());

        // Group by license name
        Map<String, List<Library>> byLicense = libraries.stream()
                .filter(l -> l.getLicenseName() != null && !l.getLicenseName().isBlank())
                .collect(Collectors.groupingBy(Library::getLicenseName));

        List<LicenseRowDto> licenses = byLicense.entrySet().stream()
                .map(entry -> LicenseRowDto.builder()
                        .name(entry.getKey())
                        .riskLevel(computeMaxRisk(entry.getValue()))
                        .libraryCount(entry.getValue().size())
                        .libraryNames(entry.getValue().stream()
                                .map(l -> l.getName() + " " + l.getVersion())
                                .sorted()
                                .collect(Collectors.toList()))
                        .build())
                .sorted(Comparator.comparingInt(dto -> riskOrdinal(dto.getRiskLevel())))
                .collect(Collectors.toList());

        long restrictedCount = licenses.stream().filter(l -> "RESTRICTED".equals(l.getRiskLevel())).count();
        long cautionCount    = licenses.stream().filter(l -> "CAUTION".equals(l.getRiskLevel())).count();
        long permittedCount  = licenses.stream().filter(l -> "PERMITTED".equals(l.getRiskLevel())).count();
        long unknownCount    = licenses.stream().filter(l -> "UNKNOWN".equals(l.getRiskLevel())).count();

        long totalObligations = libraries.stream()
                .filter(l -> l.getLicenseStatus() != LicenseStatus.PERMITTED)
                .count();

        model.addAttribute("totalLicenses", licenses.size());
        model.addAttribute("restrictedCount", restrictedCount);
        model.addAttribute("cautionCount", cautionCount);
        model.addAttribute("permittedCount", permittedCount);
        model.addAttribute("unknownCount", unknownCount);
        model.addAttribute("totalObligations", totalObligations);
        model.addAttribute("licenses", licenses);
    }

    private String computeMaxRisk(List<Library> libs) {
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.RESTRICTED)) return "RESTRICTED";
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.CAUTION))     return "CAUTION";
        if (libs.stream().anyMatch(l -> l.getLicenseStatus() == LicenseStatus.UNKNOWN))     return "UNKNOWN";
        return "PERMITTED";
    }

    private int riskOrdinal(String risk) {
        return switch (risk) {
            case "RESTRICTED" -> 0;
            case "CAUTION"    -> 1;
            case "UNKNOWN"    -> 2;
            default           -> 3; // PERMITTED
        };
    }
}
