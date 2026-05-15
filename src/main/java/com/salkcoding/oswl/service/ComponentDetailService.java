package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.DependencyPath;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.dto.DependencyPathDto;
import com.salkcoding.oswl.repository.DependencyPathRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentDetailService {

    private final ProjectRepository       projectRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final DependencyPathRepository dependencyPathRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long componentId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        Library lib = sc.getLibrary();

        String version = (sc.getScanResult().getVersion() != null)
                ? sc.getScanResult().getVersion() : "-";

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());
        model.addAttribute("projectVersion", version);
        model.addAttribute("componentId", componentId);

        model.addAttribute("componentName", lib.getName());
        model.addAttribute("componentVersion", lib.getVersion() != null ? lib.getVersion() : "-");
        model.addAttribute("reviewed", sc.isReviewed());
        model.addAttribute("patchability", patchabilityLabel(lib.computePatchability()));
        model.addAttribute("licenseRiskLabel", licenseRiskLabel(lib.getLicenseStatus(),
                lib.getLicenseName() != null && !lib.getLicenseName().isBlank()
                && lib.getLicenseStatus() == LicenseStatus.UNKNOWN));

        int secCritical = (int) lib.countBySeverity("CRITICAL");
        int secHigh     = (int) lib.countBySeverity("HIGH");
        int secMedium   = (int) lib.countBySeverity("MEDIUM");
        int secLow      = (int) lib.countBySeverity("LOW");
        int secUnscored = (int) lib.countBySeverity("NONE");
        model.addAttribute("securityCritical", secCritical);
        model.addAttribute("securityHigh",     secHigh);
        model.addAttribute("securityMedium",   secMedium);
        model.addAttribute("securityLow",      secLow);
        model.addAttribute("securityUnscored", secUnscored);
        model.addAttribute("hasVulnerabilities", secCritical + secHigh + secMedium + secLow + secUnscored > 0);
        model.addAttribute("dependencyInfo", sc.getDependencyInfo() != null ? sc.getDependencyInfo() : "-");
        model.addAttribute("ecosystem", lib.getEcosystem());

        // Full dependency path trees (empty for old scans that pre-date this feature)
        List<DependencyPathDto> pathDtos = buildPathDtos(
                dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(sc.getId()),
                project.getName());
        model.addAttribute("dependencyPaths", pathDtos);

        model.addAttribute("licenseName",
                lib.getLicenseName() != null ? lib.getLicenseName() : null);
        model.addAttribute("licenseRisk", lib.getLicenseStatus().name());
        // For UNKNOWN status: distinguish non-standard (has a name) from truly unknown (no name)
        boolean licenseIsNonStandard = lib.getLicenseStatus() == LicenseStatus.UNKNOWN
                && lib.getLicenseName() != null && !lib.getLicenseName().isBlank();
        model.addAttribute("licenseIsNonStandard", licenseIsNonStandard);
        model.addAttribute("licenseRiskLabel", licenseRiskLabel(lib.getLicenseStatus(), licenseIsNonStandard));

        // Version status for patchability context
        Boolean isLatest = lib.getIsLatestVersion();
        String deprecated = lib.getDeprecated();
        model.addAttribute("isLatestVersion", isLatest);
        model.addAttribute("isDeprecated", deprecated != null);
        model.addAttribute("deprecatedReason", deprecated);
        model.addAttribute("latestVersion", lib.getLatestVersion());

        model.addAttribute("recommendedVersion", lib.bestFixVersion());
        model.addAttribute("projectsCount", scanComponentRepository.countDistinctProjectsByLibraryId(lib.getId()));

        List<CveDto> cveDtos = lib.getCves().stream()
                .sorted(Comparator.comparingInt(c -> c.getSeverity() == null ? 999 : c.getSeverity().ordinal()))
                .map(c -> CveDto.builder()
                        .id(c.getCveId() != null ? c.getCveId() : c.getGhsaId())
                        .ghsaId(c.getGhsaId())
                        .title(c.getTitle())
                        .severity(c.getSeverity() != null ? c.getSeverity().name() : "NONE")
                        .cvssScore(c.getCvssScore() != null ? c.getCvssScore() : 0.0)
                        .cvss3Vector(c.getCvss3Vector())
                        .cweId(c.getCweId())
                        .summary(c.getSummary())
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

    private String licenseRiskLabel(LicenseStatus status, boolean isNonStandard) {
        return switch (status) {
            case RESTRICTED -> "Restricted";
            case CAUTION      -> "Caution";
            case UNKNOWN   -> isNonStandard ? "Non-standard" : "Unknown";
            default        -> "Permitted";
        };
    }

    // ── Dependency path helpers ───────────────────────────────────────────

    private List<DependencyPathDto> buildPathDtos(List<DependencyPath> paths, String rootProjectName) {
        return IntStream.range(0, paths.size())
                .mapToObj(i -> toPathDto(paths.get(i), i, rootProjectName))
                .toList();
    }

    private DependencyPathDto toPathDto(DependencyPath path, int idx, String rootProjectName) {
        List<DependencyPath.PathNode> rawNodes = path.getPathNodes();
        List<DependencyPathDto.PathNodeDto> nodeDtos = IntStream.range(0, rawNodes.size())
                .mapToObj(i -> {
                    DependencyPath.PathNode n = rawNodes.get(i);
                    boolean isRoot   = (i == 0);
                    boolean isTarget = (i == rawNodes.size() - 1);
                    // For the root node, use the project name for nicer display
                    String displayName = isRoot && (n.getName() == null || n.getName().isBlank())
                            ? rootProjectName : n.getName();
                    return DependencyPathDto.PathNodeDto.builder()
                            .name(displayName)
                            .shortName(deriveShortName(displayName))
                            .version(n.getVersion())
                            .root(isRoot)
                            .target(isTarget)
                            .index(i)
                            .build();
                })
                .toList();

        return DependencyPathDto.builder()
                .pathIndex(idx)
                .depth(path.getDepth())
                .direct(path.getDepth() == 2)
                .nodes(nodeDtos)
                .build();
    }

    /**
     * Returns the portion after the last ':' or '/', or the full string if neither exists.
     * Examples: "org.springframework:spring-web" → "spring-web",
     *           "github.com/user/repo" → "repo", "lodash" → "lodash"
     */
    private String deriveShortName(String name) {
        if (name == null || name.isBlank()) return "-";
        int colon = name.lastIndexOf(':');
        if (colon >= 0) return name.substring(colon + 1);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) return name.substring(slash + 1);
        return name;
    }
}

