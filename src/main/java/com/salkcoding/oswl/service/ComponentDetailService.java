package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private final ProjectRepository              projectRepository;
    private final ScanComponentRepository         scanComponentRepository;
    private final DependencyPathRepository         dependencyPathRepository;
    private final AuditLogService                  auditLogService;
    private final GitHubService                    gitHubService;
    private final GitLabService                    gitLabService;
    private final BitbucketService                 bitbucketService;
    private final UserVcsConnectionRepository      vcsConnectionRepository;
    private final EncryptionService                encryptionService;

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

        // Full dependency path tree (may be empty for scans created before this feature was introduced)
        List<DependencyPathDto> pathDtos = buildPathDtos(
                dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(sc.getId()),
                project.getName());
        model.addAttribute("dependencyPaths", pathDtos);

        model.addAttribute("licenseName",
                lib.getLicenseName() != null ? lib.getLicenseName() : null);
        model.addAttribute("licenseRisk", lib.getLicenseStatus().name());
        // UNKNOWN state: distinguish non-standard (name present) vs fully unknown (no name)
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

        // Deferral info
        model.addAttribute("isDeferred", sc.isDeferred());
        model.addAttribute("deferredReason", sc.getDeferralReason());
        model.addAttribute("deferralNote", sc.getDeferralNote());
        model.addAttribute("deferralExpiresAt", sc.getDeferralExpiresAt() != null
                ? sc.getDeferralExpiresAt().toLocalDate().toString() : null);
        model.addAttribute("deferredByName", sc.getDeferredByName());
        model.addAttribute("reviewedByName", sc.getReviewedByName());

        // VCS / PR creation context
        VcsProvider vcsProvider = project.getVcsProvider();
        boolean canCreatePr = project.getGithubRepo() != null && (
                vcsProvider == VcsProvider.GITHUB ||
                vcsProvider == VcsProvider.GITLAB ||
                vcsProvider == VcsProvider.BITBUCKET
        );
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
                    // For the root node, use the project name for a better display label
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
     * Returns the last part after ':' or '/'. Returns the full string if neither exists.
     * Example: "org.springframework:spring-web" → "spring-web",
     *          "github.com/user/repo" → "repo", "lodash" → "lodash"
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
     * Applies a deferral exception to the given ScanComponent (and, when scope = "all-projects",
     * to every ScanComponent referencing the same Library).
     */
    @Transactional
    public void defer(Long projectId, Long componentId, DeferralRequest req) {
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        LocalDateTime expiresAt = resolveExpiryDate(req.getExpiry(), req.getCustomDate());
        String reasonCode = buildReasonCode(req.getReason(), req.getOtherText());
        String note = req.getPrDescription() != null ? req.getPrDescription().strip() : null;
        String byName = resolveCurrentDisplayName();

        sc.applyDeferral(reasonCode, expiresAt, note, byName);

        String libName = sc.getLibrary().getName();
        String libVer  = sc.getLibrary().getVersion() != null ? sc.getLibrary().getVersion() : "-";
        String detail  = "reason=" + reasonCode
                + (expiresAt != null ? ", expires=" + expiresAt.toLocalDate() : ", expires=indefinite")
                + (note != null && !note.isBlank() ? ", note=" + note.substring(0, Math.min(100, note.length())) : "");

        if ("all-projects".equals(req.getScope())) {
            // Apply to every ScanComponent referencing the same library
            List<ScanComponent> allForLib = scanComponentRepository
                    .findAllByScanResultStatusAndLibraryId(sc.getLibrary().getId());
            for (ScanComponent other : allForLib) {
                other.applyDeferral(reasonCode, expiresAt, note, byName);
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

    /** Returns the display name of the currently authenticated user, or "unknown" if unavailable. */
    private String resolveCurrentDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OswlUserPrincipal p) {
            return p.getDisplayName();
        }
        return auth != null ? auth.getName() : "unknown";
    }

    private String buildReasonCode(String reason, String otherText) {
        if ("other".equals(reason) && otherText != null && !otherText.isBlank()) {
            return "other:" + otherText.strip().substring(0, Math.min(80, otherText.strip().length()));
        }
        return reason != null ? reason : "other";
    }

    /**
     * Creates a VCS PR/MR that upgrades the library version.
     * Supports GitHub, GitLab (MR), and Bitbucket.
     *
     * @param projectId   Project that owns the scanned component
     * @param componentId ScanComponent id
     * @param req         PR request payload (targetBranch, reviewers, prDescription)
     * @param userId      Authenticated user ID (used to look up GitLab/Bitbucket tokens in the DB)
     * @param githubToken GitHub PAT decrypted from the session (null for non-GitHub projects)
     * @return map containing "prUrl" (String) and "prNumber" (int)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> createPullRequest(Long projectId, Long componentId,
                                                  CreatePrRequest req, Long userId, String githubToken) {
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        VcsProvider provider = project.getVcsProvider();
        if (provider == null) {
            throw new IllegalStateException("This project is not connected to a VCS repository. (CLI imports do not support PR creation.)");
        }

        String repoPath = project.getGithubRepo();
        if (repoPath == null || !repoPath.contains("/")) {
            throw new IllegalStateException("This project does not have a connected VCS repository.");
        }

        Library lib    = sc.getLibrary();
        String libName = lib.getName();
        String oldVer  = lib.getVersion() != null ? lib.getVersion() : "?";
        String newVer  = lib.bestFixVersion() != null ? lib.bestFixVersion() : oldVer;
        String base    = req.getTargetBranch() != null ? req.getTargetBranch() : "main";
        String prTitle = "chore: bump " + libName + " to " + newVer + " [OsWL]";
        String body    = req.getPrDescription() != null && !req.getPrDescription().isBlank()
                ? req.getPrDescription()
                : "Bumps " + libName + " from " + oldVer + " to " + newVer + ".\n\nTriggered by OsWL.";
        List<String> reviewers = req.getReviewers() != null ? req.getReviewers() : List.of();

        Map<String, Object> result = switch (provider) {
            case GITHUB -> {
                if (githubToken == null) {
                    throw new IllegalStateException("No GitHub account is connected. Please connect one from the Settings page.");
                }
                String[] parts = repoPath.split("/", 2);
                yield gitHubService.createVersionBumpPr(
                        githubToken, parts[0], parts[1], base, libName, oldVer, newVer, prTitle, body, reviewers);
            }
            case GITLAB -> {
                UserVcsConnection conn = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.GITLAB)
                        .orElseThrow(() -> new IllegalStateException(
                                "No GitLab connection found. Please connect a GitLab token on the Settings page."));
                String token     = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                String serverUrl = conn.getServerUrl();
                yield gitLabService.createVersionBumpMr(
                        token, serverUrl, repoPath, base, libName, oldVer, newVer, prTitle, body, reviewers);
            }
            case BITBUCKET -> {
                UserVcsConnection conn = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.BITBUCKET)
                        .orElseThrow(() -> new IllegalStateException(
                                "No Bitbucket connection found. Please connect a Bitbucket token on the Settings page."));
                String token     = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                String username  = conn.getVcsUsername();
                String serverUrl = conn.getServerUrl();
                yield bitbucketService.createVersionBumpPr(
                        token, username, serverUrl, repoPath, base, libName, oldVer, newVer, prTitle, body, reviewers);
            }
        };

        auditLogService.log("COMPONENT.CREATE_PR", "COMPONENT",
                componentId.toString(), libName + " " + oldVer,
                "provider=" + provider + ", repo=" + repoPath + ", branch=" + base + ", pr=" + result.get("prNumber"));

        log.info("[CreatePR] provider={} projectId={} componentId={} prUrl={}",
                provider, projectId, componentId, result.get("prUrl"));
        return result;
    }
}

