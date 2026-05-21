package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
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

        // 전체 의존성 경로 트리 (이 기능 도입 이전 스캔은 비어 있을 수 있음)
        List<DependencyPathDto> pathDtos = buildPathDtos(
                dependencyPathRepository.findByScanComponentIdOrderByPathIndexAsc(sc.getId()),
                project.getName());
        model.addAttribute("dependencyPaths", pathDtos);

        model.addAttribute("licenseName",
                lib.getLicenseName() != null ? lib.getLicenseName() : null);
        model.addAttribute("licenseRisk", lib.getLicenseStatus().name());
        // UNKNOWN 상태: 비표준(이름 있음) vs 완전 미상(이름 없음) 구분
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

        // VCS / PR 생성 컨텍스트
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
                    // 루트 노드의 경우 더 나은 표시를 위해 프로젝트 이름 사용
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
     * ':' 또는 '/' 뒤의 마지막 부분을 반환한다. 없으면 전체 문자열 반환.
     * 예: "org.springframework:spring-web" → "spring-web",
     *     "github.com/user/repo" → "repo", "lodash" → "lodash"
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
     * 주어진 ScanComponent(및 scope = "all-projects"인 경우 동일 Library를 참조하는
     * 모든 ScanComponent)에 유예 예외를 적용한다.
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
            // 동일 라이브러리를 참조하는 모든 ScanComponent에 적용
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
     * 라이브러리 버전을 업그레이드하는 VCS PR/MR을 생성한다.
     * GitHub, GitLab(MR), Bitbucket을 지원한다.
     *
     * @param projectId   스캔 컴포넌트를 소유한 프로젝트
     * @param componentId ScanComponent id
     * @param req         PR 요청 페이로드 (targetBranch, reviewers, prDescription)
     * @param userId      인증된 사용자 ID (DB에서 GitLab/Bitbucket 토큰 조회용)
     * @param githubToken 세션에서 복호화된 GitHub PAT (GitHub 프로젝트가 아닌 경우 null)
     * @return "prUrl"(String)과 "prNumber"(int)이 담긴 map
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
            throw new IllegalStateException("이 프로젝트는 VCS 저장소와 연결되어 있지 않습니다. (CLI import는 PR 생성을 지원하지 않습니다)");
        }

        String repoPath = project.getGithubRepo();
        if (repoPath == null || !repoPath.contains("/")) {
            throw new IllegalStateException("이 프로젝트에는 연결된 VCS 저장소가 없습니다.");
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
                    throw new IllegalStateException("GitHub 계정이 연결되어 있지 않습니다. 설정 페이지에서 연결해 주세요.");
                }
                String[] parts = repoPath.split("/", 2);
                yield gitHubService.createVersionBumpPr(
                        githubToken, parts[0], parts[1], base, libName, oldVer, newVer, prTitle, body, reviewers);
            }
            case GITLAB -> {
                UserVcsConnection conn = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.GITLAB)
                        .orElseThrow(() -> new IllegalStateException(
                                "GitLab 연결이 없습니다. 설정 페이지에서 GitLab 토큰을 연결해 주세요."));
                String token     = encryptionService.decrypt(conn.getAccessTokenEncrypted());
                String serverUrl = conn.getServerUrl();
                yield gitLabService.createVersionBumpMr(
                        token, serverUrl, repoPath, base, libName, oldVer, newVer, prTitle, body, reviewers);
            }
            case BITBUCKET -> {
                UserVcsConnection conn = vcsConnectionRepository
                        .findByUserIdAndProviderAndActiveTrue(userId, VcsProvider.BITBUCKET)
                        .orElseThrow(() -> new IllegalStateException(
                                "Bitbucket 연결이 없습니다. 설정 페이지에서 Bitbucket 토큰을 연결해 주세요."));
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

