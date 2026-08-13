package com.salkcoding.oswl.service.scan;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import com.salkcoding.oswl.service.vcs.GitLabService;
import com.salkcoding.oswl.service.vcs.GitHubService;
import com.salkcoding.oswl.service.vcs.BitbucketService;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.EpssClient;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.scan.DependencyPath;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.CveDto;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.dto.DependencyPathDto;
import com.salkcoding.oswl.exception.AiSummaryException;
import com.salkcoding.oswl.exception.AiSummaryFailureReason;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.repository.scan.DependencyPathRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import com.salkcoding.oswl.service.ai.AiPreferencesService;
import com.salkcoding.oswl.service.ai.AiStructuredSummary;
import com.salkcoding.oswl.service.ai.AiLanguageContext;
import com.salkcoding.oswl.service.ai.AiUsageContext;
import com.salkcoding.oswl.service.cvss.CvssV3Calculator;
import com.salkcoding.oswl.service.cvss.CvssV4Calculator;
import com.salkcoding.oswl.service.cvss.CvssVectorVersion;
import com.salkcoding.oswl.service.cvss.EnvironmentalRequirementMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComponentDetailService {

    private final ProjectRepository              projectRepository;
    private final ScanComponentRepository         scanComponentRepository;
    private final com.salkcoding.oswl.repository.scan.ScanResultRepository scanResultRepository;
    private final DependencyPathRepository         dependencyPathRepository;
    private final AuditLogService                  auditLogService;
    private final GitHubService                    gitHubService;
    private final GitLabService                    gitLabService;
    private final BitbucketService                 bitbucketService;
    private final UserVcsConnectionRepository      vcsConnectionRepository;
    private final EncryptionService                encryptionService;
    private final CveRepository                    cveRepository;
    private final AiAnalysisService                aiAnalysisService;
    private final AiPreferencesService               aiPreferencesService;
    private final KevCatalogService                kevCatalogService;
    private final EpssClient                       epssClient;
    private final ProjectAccessService             projectAccessService;

    /** deferral_reason column allows at most 50 characters (see ScanComponent). */
    private static final int DEFERRAL_REASON_MAX_LENGTH = 50;

    @Transactional
    public CveDto regenerateCveAiSummary(Long projectId, Long componentId, Long cveDbId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ScanComponent sc = scanComponentRepository
                .findByIdAndProjectIdWithCves(componentId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("Component not found: " + componentId));
        Library lib = sc.getLibrary();
        Cve cve = lib.getCves().stream()
                .filter(c -> c.getId().equals(cveDbId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("CVE not found on component: " + cveDbId));

        String cveId = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
        if (cve.getCveId() != null) {
            var epss = epssClient.fetchScores(List.of(cve.getCveId()));
            cve.setThreatIntel(epss.get(cve.getCveId().toUpperCase()),
                    kevCatalogService.isListed(cve.getCveId()));
            cveRepository.save(cve);
        }

        String deployment = project.getDeploymentProfile() != null
                ? project.getDeploymentProfile().name()
                : aiPreferencesService.getEffective().getDefaultDeploymentProfile().name();

        var request = new AiAnalysisService.CveSummaryRequest(
                cveId != null ? cveId : "unknown",
                cve.getSeverity() != null ? cve.getSeverity().name() : "NONE",
                cve.getCvssScore() != null ? cve.getCvssScore() : 0.0,
                lib.getName() + " " + lib.getVersion(),
                cve.getTitle(), cve.getSummary(), cve.getFixVersion(),
                cve.getCweId(), cve.getCvss3Vector(),
                "direct", lib.computePatchability().name(),
                cve.getEpssScore(), Boolean.TRUE.equals(cve.getKevListed()));

        AiAnalysisService.CveSummarizeOutcome outcome;
        try (var ignored = AiUsageContext.scope(project.getName());
             var ignoredLang = AiLanguageContext.scope(
                     org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage())) {
            outcome = aiAnalysisService.summarizeCveWithOutcome(request, deployment);
        }
        if (!outcome.success()) {
            AiSummaryFailureReason reason = outcome.failure() != null
                    ? outcome.failure() : AiSummaryFailureReason.UNKNOWN;
            auditLogService.log("COMPONENT.CVE_AI_REGENERATE_FAILED", "CVE",
                    String.valueOf(cveDbId), cveId,
                    "projectId=" + projectId + ", componentId=" + componentId
                            + ", reason=" + reason.name());
            throw new AiSummaryException(reason, outcome.failureArgs());
        }
        AiStructuredSummary.ParsedEntry entry = outcome.entry();
        cve.setAiTriage(entry.summary(), entry.priority(), entry.recommendedAction());
        cveRepository.save(cve);
        auditLogService.log("COMPONENT.CVE_AI_REGENERATE", "CVE",
                String.valueOf(cveDbId), cveId,
                "projectId=" + projectId + ", componentId=" + componentId
                        + ", lib=" + lib.getName() + " " + lib.getVersion()
                        + ", deployment=" + deployment + ", priority=" + entry.priority());

        return CveDto.builder()
                .id(cveId)
                .ghsaId(cve.getGhsaId())
                .title(cve.getTitle())
                .severity(cve.getSeverity() != null ? cve.getSeverity().name() : "NONE")
                .cvssScore(cve.getCvssScore() != null ? cve.getCvssScore() : 0.0)
                .cvss3Vector(cve.getCvss3Vector())
                .cweId(cve.getCweId())
                .summary(cve.getSummary())
                .fixVersion(cve.getFixVersion())
                .aiSummary(cve.getAiSummary())
                .aiPriority(cve.getAiPriority())
                .aiRecommendedAction(cve.getAiRecommendedAction())
                .epssScore(cve.getEpssScore())
                .kevListed(cve.getKevListed())
                .cveDbId(cve.getId())
                .environmentalScore(computeEnvironmentalScore(
                        cve.getCvss3Vector(), project.getDeploymentProfile(), sc.isRuntimeScope()))
                .build();
    }

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

        // CVE/OSV에 문서화된 수정 버전만 보안 권장 버전으로 사용 (deps.dev 최신 버전과 혼동하지 않음)
        String securityFixVersion = lib.bestFixVersion();
        model.addAttribute("securityFixVersion", securityFixVersion);
        model.addAttribute("recommendedVersion", securityFixVersion);

        // PR target: patch (CVE fix) version first, else latest when outdated; null → no PR
        model.addAttribute("prTargetVersion", lib.resolvePrTargetVersion());
        model.addAttribute("projectsCount", scanComponentRepository.countDistinctProjectsByLibraryId(lib.getId()));

        // Package health: OpenSSF Scorecard score (null when deps.dev has none) + malicious flag (OSV MAL-)
        model.addAttribute("scorecardScore", lib.getScorecardScore());
        // Upstream identity (deps.dev project record) — lets the description say what the
        // component actually is instead of restating badges shown above it.
        model.addAttribute("componentDescription", lib.getDescription());
        model.addAttribute("componentHomepage", lib.getHomepage());
        model.addAttribute("componentSourceRepoUrl", lib.getSourceRepoUrl());
        model.addAttribute("malicious", lib.isMalicious());

        // Supply-chain heuristics: possible typosquat / dependency-confusion flag
        model.addAttribute("typosquatRisk", lib.isTyposquatRisk());
        model.addAttribute("typosquatReason", lib.getTyposquatReason());

        // Jira integration: existing linked issue, if any
        model.addAttribute("jiraIssueKey", sc.getJiraIssueKey());
        model.addAttribute("jiraIssueUrl", sc.getJiraIssueUrl());

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
                        .cvssScore(resolveCvssScore(c))
                        .cvss3Vector(c.getCvss3Vector())
                        .cweId(c.getCweId())
                        .summary(c.getSummary())
                        .fixVersion(c.getFixVersion())
                        .aiSummary(c.getAiSummary())
                        .aiPriority(c.getAiPriority())
                        .aiRecommendedAction(c.getAiRecommendedAction())
                        .epssScore(c.getEpssScore())
                        .kevListed(c.getKevListed())
                        .cveDbId(c.getId())
                        .environmentalScore(computeEnvironmentalScore(
                                c.getCvss3Vector(), project.getDeploymentProfile(), sc.isRuntimeScope()))
                        .build())
                .collect(Collectors.toList());
        model.addAttribute("cves", cveDtos);
    }

    /**
     * The CVE's displayed score — the score reported directly by the data source (deps.dev/NVD/
     * GitHub Advisory) when present, falling back to computing the CVSS Base Score from the
     * stored vector (v3.x via {@link CvssV3Calculator}, v4.0 via {@link CvssV4Calculator}) so a
     * CVE that supplied a vector without an accompanying score still shows one. {@code 0.0} when
     * neither is available (no CVSS data at all).
     */
    private double resolveCvssScore(Cve c) {
        if (c.getCvssScore() != null) {
            return c.getCvssScore();
        }
        String vector = c.getCvss3Vector();
        Double fromVector = switch (CvssVectorVersion.detect(vector)) {
            case V3 -> CvssV3Calculator.baseScore(vector);
            case V4 -> CvssV4Calculator.baseScore(vector);
            case UNKNOWN -> null;
        };
        return fromVector != null ? fromVector : 0.0;
    }

    /**
     * CVSS Environmental score for one CVE (ROADMAP A5) — {@code null} when the CVE has no
     * recognized CVSS vector at all. Dispatches to {@link CvssV3Calculator} or
     * {@link CvssV4Calculator} depending on which version the stored vector declares.
     */
    private Double computeEnvironmentalScore(String cvssVector, DeploymentProfile deploymentProfile,
                                             boolean runtimeScope) {
        if (cvssVector == null || cvssVector.isBlank()) {
            return null;
        }
        var req = EnvironmentalRequirementMapper.resolve(deploymentProfile, runtimeScope);
        return switch (CvssVectorVersion.detect(cvssVector)) {
            case V3 -> CvssV3Calculator.environmentalScore(
                    cvssVector, req.confidentiality(), req.integrity(), req.availability());
            case V4 -> CvssV4Calculator.environmentalScore(
                    cvssVector, req.confidentiality(), req.integrity(), req.availability());
            case UNKNOWN -> null;
        };
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
     * to ScanComponents referencing the same Library — restricted to projects the current
     * user can access).
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
            // Propagate only to projects the current user can access (cross-project IDOR prevention).
            // Falls back to the current component only when no accessible project can be resolved.
            List<Long> accessibleIds = projectAccessService.accessibleProjectIds();
            List<ScanComponent> allForLib = accessibleIds.isEmpty()
                    ? List.of(sc)
                    : scanComponentRepository.findAllByLibraryIdAndProjectIdIn(
                            sc.getLibrary().getId(), accessibleIds);
            for (ScanComponent other : allForLib) {
                other.applyDeferral(reasonCode, expiresAt, note, byName);
            }
            auditLogService.log("COMPONENT.DEFER_ALL", "LIBRARY",
                    sc.getLibrary().getId().toString(), libName + " " + libVer,
                    detail + ", applied=" + allForLib.size() + " components (accessible projects only)");
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
                LocalDate parsed;
                try {
                    parsed = LocalDate.parse(customDate != null ? customDate.strip() : "");
                } catch (Exception e) {
                    throw new InvalidRequestException("Invalid expiry date — use the YYYY-MM-DD format.");
                }
                if (!parsed.isAfter(today)) {
                    throw new InvalidRequestException("Expiry date must be a future date.");
                }
                yield parsed.atStartOfDay();
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
        String code = ("other".equals(reason) && otherText != null && !otherText.isBlank())
                ? "other:" + otherText.strip()
                : (reason != null ? reason : "other");
        // deferral_reason is varchar(50) — truncate the final string without splitting a surrogate pair
        if (code.length() > DEFERRAL_REASON_MAX_LENGTH) {
            int end = DEFERRAL_REASON_MAX_LENGTH;
            if (Character.isHighSurrogate(code.charAt(end - 1))) end--;
            code = code.substring(0, end);
        }
        return code;
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

        return createPullRequest(sc, project, req, userId, githubToken);
    }

    /**
     * PR creation with the component and project already loaded — the batch path passes its
     * pre-loaded entities in so the per-component loop doesn't re-run the component/project
     * lookups for every candidate.
     */
    private Map<String, Object> createPullRequest(ScanComponent sc, Project project,
                                                  CreatePrRequest req, Long userId, String githubToken) {
        Long projectId = project.getId();
        Long componentId = sc.getId();

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
        String newVer  = lib.resolvePrTargetVersion();
        if (newVer == null || newVer.isBlank()) {
            throw new IllegalStateException(
                    "No patch or newer version is available for this component.");
        }
        if (req.getTargetBranch() == null || req.getTargetBranch().isBlank()) {
            throw new InvalidRequestException("Target branch is required.");
        }
        String base = req.getTargetBranch().strip();
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
                String githubServerUrl = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.GITHUB)
                        .map(UserVcsConnection::getServerUrl)
                        .orElse(null);
                yield gitHubService.createVersionBumpPr(
                        githubToken, parts[0], parts[1], base, libName, oldVer, newVer, prTitle, body, reviewers,
                        githubServerUrl);
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

    /**
     * Batch upgrade PRs (Renovate-lite): creates one PR per patchable component of the latest
     * completed scan. Reuses the single-PR path per component; a failure on one component is
     * captured and the rest proceed (partial success). Deferred/ignored components are skipped.
     *
     * @return per-component outcomes: {componentId, name, currentVersion, targetVersion, success, prUrl|error}
     */
    @Transactional
    public Map<String, Object> createBatchPullRequests(Long projectId, String baseBranch,
                                                       Long userId, String githubToken) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.getVcsProvider() == null || project.getGithubRepo() == null) {
            throw new IllegalStateException(
                    "This project is not connected to a VCS repository. (CLI imports do not support PR creation.)");
        }
        if (baseBranch == null || baseBranch.isBlank()) {
            throw new InvalidRequestException("Target branch is required.");
        }

        ScanResult scan = scanResultRepository.findRecentCompleted(projectId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Project has no completed scan."));
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());

        // One PR per distinct patchable library; skip accepted exceptions.
        java.util.Map<Long, ScanComponent> patchable = new java.util.LinkedHashMap<>();
        for (ScanComponent sc : components) {
            if (sc.isDeferred() || sc.isIgnored()) continue;
            Library lib = sc.getLibrary();
            if (lib.resolvePrTargetVersion() == null) continue;
            patchable.putIfAbsent(lib.getId(), sc);
        }

        List<Map<String, Object>> outcomes = new ArrayList<>();
        int created = 0, failed = 0;
        for (ScanComponent sc : patchable.values()) {
            Library lib = sc.getLibrary();
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("componentId", sc.getId());
            row.put("name", lib.getName());
            row.put("currentVersion", lib.getVersion());
            row.put("targetVersion", lib.resolvePrTargetVersion());
            try {
                CreatePrRequest req = CreatePrRequest.ofBranch(baseBranch);
                Map<String, Object> result = createPullRequest(sc, project, req, userId, githubToken);
                row.put("success", true);
                row.put("prUrl", result.get("prUrl"));
                created++;
            } catch (Exception e) {
                row.put("success", false);
                row.put("error", e.getMessage());
                failed++;
                log.warn("[BatchPR] projectId={} componentId={} failed: {}", projectId, sc.getId(), e.getMessage());
            }
            outcomes.add(row);
        }

        auditLogService.log("PROJECT.BATCH_PR", "PROJECT", projectId.toString(), project.getName(),
                "branch=" + baseBranch + " candidates=" + patchable.size()
                        + " created=" + created + " failed=" + failed);
        log.info("[BatchPR] projectId={} candidates={} created={} failed={}",
                projectId, patchable.size(), created, failed);

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("candidates", patchable.size());
        response.put("created", created);
        response.put("failed", failed);
        response.put("results", outcomes);
        return response;
    }
}

