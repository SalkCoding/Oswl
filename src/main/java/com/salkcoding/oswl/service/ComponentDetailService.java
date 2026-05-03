package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.repository.ComponentRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ComponentDetailService {

    private final ProjectRepository projectRepository;
    private final ComponentRepository componentRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long componentId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        OswlComponent comp = componentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        String version = (comp.getScanResult().getVersion() != null)
                ? comp.getScanResult().getVersion() : "-";

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());
        model.addAttribute("projectVersion", version);
        model.addAttribute("componentId", componentId);

        model.addAttribute("componentName", comp.getName());
        model.addAttribute("componentVersion", comp.getVersion() != null ? comp.getVersion() : "-");
        model.addAttribute("reviewed", comp.isReviewed());
        model.addAttribute("patchability", patchabilityLabel(comp.getPatchability()));
        model.addAttribute("licenseRiskLabel", licenseRiskLabel(comp.getLicenseStatus()));

        model.addAttribute("securityCritical", (int) comp.countBySeverity("CRITICAL"));
        model.addAttribute("securityHigh",     (int) comp.countBySeverity("HIGH"));
        model.addAttribute("securityMedium",   (int) comp.countBySeverity("MEDIUM"));
        model.addAttribute("securityLow",      (int) comp.countBySeverity("LOW"));

        model.addAttribute("licenseName",
                comp.getLicenseName() != null ? comp.getLicenseName() : "-");
        model.addAttribute("licenseRisk", comp.getLicenseStatus().name());

        // 가장 낮은 심각도 CVE에서 fixVersion 추출 (첫 번째 CRITICAL → HIGH 순)
        String recommendedVersion = comp.getCves().stream()
                .sorted(Comparator.comparingInt(c -> c.getSeverity().ordinal()))
                .filter(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank())
                .map(Cve::getFixVersion)
                .findFirst()
                .orElse(null);
        model.addAttribute("recommendedVersion", recommendedVersion);
        model.addAttribute("projectsCount", 0);

        List<CveDto> cveDtos = comp.getCves().stream()
                .sorted(Comparator.comparingInt(c -> c.getSeverity().ordinal()))
                .map(c -> CveDto.builder()
                        .id(c.getCveId())
                        .severity(c.getSeverity().name())
                        .cvssScore(c.getCvssScore() != null ? c.getCvssScore() : 0.0)
                        .type(c.getType())
                        .discoveredOn(c.getDiscoveredOn())
                        .affects(c.getAffects())
                        .fixVersion(c.getFixVersion())
                        .aiSummary(c.getAiSummary())
                        .build())
                .collect(Collectors.toList());
        model.addAttribute("cves", cveDtos);
    }

    private String patchabilityLabel(com.salkcoding.oswl.domain.enums.Patchability p) {
        return switch (p) {
            case PATCHABLE     -> "patchable";
            case NON_PATCHABLE -> "non-patchable";
            default            -> "unknown";
        };
    }

    private String licenseRiskLabel(LicenseStatus status) {
        return switch (status) {
            case VIOLATION -> "A Critical Risk License";
            case WARN      -> "A High Risk License";
            default        -> "A Low Risk License";
        };
    }
}
