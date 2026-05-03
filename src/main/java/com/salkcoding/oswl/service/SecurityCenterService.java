package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.ComponentRowDto;
import com.salkcoding.oswl.repository.ComponentRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SecurityCenterService {

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
            model.addAttribute("securityCritical", 0);
            model.addAttribute("securityHigh", 0);
            model.addAttribute("securityMedium", 0);
            model.addAttribute("securityLow", 0);
            model.addAttribute("licenseCritical", 0);
            model.addAttribute("licenseHigh", 0);
            model.addAttribute("licenseMedium", 0);
            model.addAttribute("licenseLow", 0);
            model.addAttribute("components", List.of());
            return;
        }

        model.addAttribute("projectVersion", scan.getVersion() != null ? scan.getVersion() : "-");

        List<OswlComponent> components = componentRepository.findByScanResultIdWithCves(scan.getId());

        int secCritical = 0, secHigh = 0, secMedium = 0, secLow = 0;
        int licCritical = 0, licHigh = 0, licMedium = 0, licLow = 0;
        List<ComponentRowDto> rows = new ArrayList<>();

        for (OswlComponent comp : components) {
            int c = (int) comp.countBySeverity("CRITICAL");
            int h = (int) comp.countBySeverity("HIGH");
            int m = (int) comp.countBySeverity("MEDIUM");
            int l = (int) comp.countBySeverity("LOW");

            secCritical += c;
            secHigh     += h;
            secMedium   += m;
            secLow      += l;

            switch (comp.getLicenseStatus()) {
                case VIOLATION -> licCritical++;
                case WARN      -> licHigh++;
                default        -> licLow++;
            }

            rows.add(ComponentRowDto.builder()
                    .id(comp.getId())
                    .name(comp.getName())
                    .version(comp.getVersion())
                    .dependencyInfo(comp.getDependencyInfo())
                    .reviewed(comp.isReviewed())
                    .securityCritical(c)
                    .securityHigh(h)
                    .securityMedium(m)
                    .securityLow(l)
                    .patchability(patchabilityLabel(comp.getPatchability()))
                    .licenseStatus(comp.getLicenseStatus().name())
                    .licenseName(comp.getLicenseName())
                    .build());
        }

        model.addAttribute("securityCritical", secCritical);
        model.addAttribute("securityHigh", secHigh);
        model.addAttribute("securityMedium", secMedium);
        model.addAttribute("securityLow", secLow);
        model.addAttribute("licenseCritical", licCritical);
        model.addAttribute("licenseHigh", licHigh);
        model.addAttribute("licenseMedium", licMedium);
        model.addAttribute("licenseLow", licLow);
        model.addAttribute("components", rows);
    }

    private String patchabilityLabel(com.salkcoding.oswl.domain.enums.Patchability p) {
        return switch (p) {
            case PATCHABLE     -> "patchable";
            case NON_PATCHABLE -> "non-patchable";
            default            -> "unknown";
        };
    }
}
