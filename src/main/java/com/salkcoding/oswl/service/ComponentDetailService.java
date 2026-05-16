package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.DependencyPath;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.dto.DependencyPathDto;
import com.salkcoding.oswl.repository.DependencyPathRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentDetailService {

    private final ProjectRepository       projectRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final DependencyPathRepository dependencyPathRepository;
    private final AuditLogService          auditLogService;
    private final GitHubService            gitHubService;

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

        // VCS / PR-creation context
        VcsProvider vcsProvider = project.getVcsProvider();
        boolean canCreatePr = vcsProvider == VcsProvider.GITHUB && project.getGithubRepo() != null;
        model.addAttribute("vcsProvider", vcsProvider != null ? vcsProvider.name() : null);
        model.addAttribute("canCreatePr", canCreatePr);

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

    /**
     * Applies a deferral exception to the given ScanComponent (and optionally
     * to all ScanComponents that reference the same Library, when scope = "all-projects").
     */
    @Transactional
    public void defer(Long projectId, Long componentId, DeferralRequest req) {
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        LocalDateTime expiresAt = resolveExpiryDate(req.getExpiry(), req.getCustomDate());
        String reasonCode = buildReasonCode(req.getReason(), req.getOtherText());
        String note = req.getPrDescription() != null ? req.getPrDescription().strip() : null;

        sc.applyDeferral(reasonCode, expiresAt, note);

        String libName = sc.getLibrary().getName();
        String libVer  = sc.getLibrary().getVersion() != null ? sc.getLibrary().getVersion() : "-";
        String detail  = "reason=" + reasonCode
                + (expiresAt != null ? ", expires=" + expiresAt.toLocalDate() : ", expires=indefinite")
                + (note != null && !note.isBlank() ? ", note=" + note.substring(0, Math.min(100, note.length())) : "");

        if ("all-projects".equals(req.getScope())) {
            // Apply to all ScanComponents that reference the same library
            List<ScanComponent> allForLib = scanComponentRepository
                    .findAllByScanResultStatusAndLibraryId(sc.getLibrary().getId());
            for (ScanComponent other : allForLib) {
                other.applyDeferral(reasonCode, expiresAt, note);
            }
            auditLogService.log("COMPONENT.DEFER_ALL", "LIBRARY",
                    sc.getLibrary().getId().toString(), libName + " " + libVer, detail);
        } else {
            auditLogService.log("COMPONENT.DEFER", "COMPONENT",
                    componentId.toString(), libName + " " + libVer, detail);
        }

        log.info("[Defer] component={} library={} {} reason={} expires={}",
                componentId, libName, libVer, reasonCode, expiresAt);
    }

    private LocalDateTime resolveExpiryDate(String expiry, String customDate) {
        if (expiry == null || "indefinite".equals(expiry)) return null;
        LocalDate today = LocalDate.now();
        return switch (expiry) {
            case "1-week"  -> today.plusWeeks(1).atStartOfDay();
            case "1-month" -> today.plusMonths(1).atStartOfDay();
            case "3-month" -> today.plusMonths(3).atStartOfDay();
            case "6-month" -> today.plusMonths(6).atStartOfDay();
            case "custom"  -> {
                try { yield LocalDate.parse(customDate).atStartOfDay(); }
                catch (Exception e) { yield today.plusMonths(1).atStartOfDay(); }
            }
            default -> null;
        };
    }

    private String buildReasonCode(String reason, String otherText) {
        if ("other".equals(reason) && otherText != null && !otherText.isBlank()) {
            return "other:" + otherText.strip().substring(0, Math.min(80, otherText.strip().length()));
        }
        return reason != null ? reason : "other";
    }

    /**
     * Creates a GitHub pull request that bumps the library version.
     *
     * @param projectId   project owning this scan component
     * @param componentId ScanComponent id
     * @param req         PR request payload (targetBranch, reviewers, prDescription)
     * @param githubToken decrypted GitHub PAT
     * @return map with "prUrl" (String) and "prNumber" (int)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> createPullRequest(Long projectId, Long componentId,
                                                  CreatePrRequest req, String githubToken) {
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Only GitHub projects support automated PR creation
        VcsProvider provider = project.getVcsProvider();
        if (provider == null) {
            throw new IllegalStateException("이 프로젝트는 VCS 저장소와 연결되어 있지 않습니다. (CLI import는 PR 생성을 지원하지 않습니다)");
        }
        if (provider != VcsProvider.GITHUB) {
            throw new IllegalStateException(provider.name() + " PR 자동 생성은 현재 지원되지 않습니다. GitHub 연결 프로젝트에서만 사용할 수 있습니다.");
        }

        String githubRepo = project.getGithubRepo();
        if (githubRepo == null || !githubRepo.contains("/")) {
            throw new IllegalStateException("Project does not have a connected GitHub repository.");
        }
        String[] parts  = githubRepo.split("/", 2);
        String owner    = parts[0];
        String repo     = parts[1];

        Library lib     = sc.getLibrary();
        String libName  = lib.getName();
        String oldVer   = lib.getVersion() != null ? lib.getVersion() : "?";
        String newVer   = lib.bestFixVersion() != null ? lib.bestFixVersion() : oldVer;
        String base     = req.getTargetBranch() != null ? req.getTargetBranch() : "main";
        String prTitle  = "chore: bump " + libName + " to " + newVer + " [OsWL]";
        String body     = req.getPrDescription() != null && !req.getPrDescription().isBlank()
                ? req.getPrDescription()
                : "Bumps " + libName + " from " + oldVer + " to " + newVer + ".\n\nTriggered by OsWL.";

        Map<String, Object> result = gitHubService.createVersionBumpPr(
                githubToken, owner, repo, base, libName, oldVer, newVer, prTitle, body,
                req.getReviewers() != null ? req.getReviewers() : List.of()
        );

        auditLogService.log("COMPONENT.CREATE_PR", "COMPONENT",
                componentId.toString(), libName + " " + oldVer,
                "repo=" + githubRepo + ", branch=" + base + ", pr=" + result.get("prNumber"));

        log.info("[CreatePR] projectId={} componentId={} prUrl={}", projectId, componentId, result.get("prUrl"));
        return result;
    }
}

