package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.LicenseRowDto;
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
    public void populateModel(Long projectId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        ScanResult scan = scanResultRepository
                .findFirstByProjectIdAndStatusOrderByScannedAtDesc(projectId, ScanStatus.COMPLETED)
                .orElse(null);

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

        // 라이선스 이름 기준으로 그룹핑
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

        // VIOLATION + WARN 컴포넌트 수 = 의무 사항이 있는 컴포넌트 수
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
