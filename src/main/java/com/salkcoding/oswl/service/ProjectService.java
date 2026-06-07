package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectVersion;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.domain.enums.ImportSource;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.dto.TrashProjectDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ProjectVersionRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.aop.Auditable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectVersionRepository projectVersionRepository;
    private final ScanResultRepository scanResultRepository;
    private final AuditLogService auditLogService;
    private final ProjectAccessService projectAccessService;

    @Transactional(readOnly = true)
    public List<ProjectSummaryDto> findAll() {
        List<Long> accessible = projectAccessService.accessibleProjectIds();
        if (accessible.isEmpty()) {
            return List.of();
        }
        return projectRepository.findAllByDeletedAtIsNullAndIdInOrderByCreatedAtDesc(accessible).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TrashProjectDto> findTrash() {
        var accessible = Set.copyOf(projectAccessService.accessibleProjectIds());
        return projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc().stream()
                .filter(p -> accessible.contains(p.getId()))
                .map(this::toTrash)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Project getById(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        projectAccessService.assertCanViewProject(id);
        return project;
    }

    @Transactional
    @Auditable(action = "PROJECT.CREATE", targetType = "PROJECT",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public Project create(String name) {
        Long creatorId = projectAccessService.currentUserIdOrNull();
        Project project = Project.builder()
                .name(name)
                .createdByUserId(creatorId)
                .build();
        Project saved = projectRepository.save(project);
        if (creatorId != null) {
            projectAccessService.ensureMember(saved.getId(), creatorId, ProjectMemberRole.ADMIN);
        }
        log.info("[Project] Created id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Find-or-create a Project for the given GitHub owner/repo, then upsert a
     * {@link ProjectVersion} for the given branch.
     *
     * <ul>
     *   <li>Same owner/repo + same branch → no new {@link ProjectVersion} row.</li>
     *   <li>Same owner/repo + new branch → create a new version with the next sequential number.</li>
     *   <li>New owner/repo → create a new Project (UUID auto-generated) and first version.</li>
     * </ul>
     *
     * @param createdByUserId user who initiated the import; stored only on first creation
     * @return the Project (existing or newly created)
     */
    @Transactional
    public Project upsertFromGitHub(VcsProvider provider, String owner, String repo, String branch, Long createdByUserId) {
        String repoKey = owner + "/" + repo;

        // 1. Find or create the logical project
        boolean isNewProject = projectRepository.findByGithubRepo(repoKey).isEmpty();
        Project project = projectRepository.findByGithubRepo(repoKey)
                .orElseGet(() -> projectRepository.save(
                        Project.builder().name(repoKey).createdByUserId(createdByUserId).build()
                ));

        // 2. Create branch-level version row on first import of this branch
        if (projectVersionRepository.findByProjectAndBranch(project, branch).isEmpty()) {
            int nextNum = projectVersionRepository.findMaxVersionNumber(project) + 1;
            projectVersionRepository.save(ProjectVersion.builder()
                    .project(project)
                    .branch(branch)
                    .versionNumber(nextNum)
                    .importSource(ImportSource.GIT)
                    .build());
        }

        // 3. Update denormalized fields on the project
        project.markGithubImport(provider, owner, repo, branch);
        Project saved = projectRepository.save(project);
        if (createdByUserId != null) {
            projectAccessService.ensureMember(saved.getId(), createdByUserId, ProjectMemberRole.ADMIN);
        } else {
            projectAccessService.ensureCreatorMemberIfAbsent(saved);
        }
        if (isNewProject) {
            auditLogService.log("PROJECT.CREATE", "PROJECT",
                    saved.getId().toString(), saved.getName(),
                    "import=" + provider + " repo=" + repoKey);
        }
        log.info("[Project] {} import projectId={} repo={} branch={}", provider, saved.getId(), repoKey, branch);
        return saved;
    }

    @Transactional
    public Project upsertFromGitHub(String owner, String repo, String branch, Long createdByUserId) {
        return upsertFromGitHub(VcsProvider.GITHUB, owner, repo, branch, createdByUserId);
    }

    /** Backward-compat overload — caller does not know the user (e.g. GitHubApiController). */
    @Transactional
    public Project upsertFromGitHub(String owner, String repo, String branch) {
        return upsertFromGitHub(owner, repo, branch, null);
    }

    /** Soft-delete: moves the project to trash. */
    @Transactional
    @Auditable(action = "PROJECT.DELETE", targetType = "PROJECT",
               targetIdExpr = "#id.toString()", when = Auditable.When.BEFORE)
    public void delete(Long id) {
        projectAccessService.assertCanViewProject(id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        project.softDelete();
        projectRepository.save(project);
        log.info("[Project] Soft-deleted id={}", id);
    }

    @Transactional
    @Auditable(action = "PROJECT.RESTORE", targetType = "PROJECT",
               targetIdExpr = "#id.toString()", when = Auditable.When.BEFORE)
    public void restore(Long id) {
        projectAccessService.assertCanViewProject(id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        project.restore();
        projectRepository.save(project);
        log.info("[Project] Restored id={}", id);
    }

    @Transactional
    @Auditable(action = "PROJECT.PERMANENT_DELETE", targetType = "PROJECT",
               targetIdExpr = "#id.toString()", when = Auditable.When.BEFORE)
    public void permanentDelete(Long id) {
        projectAccessService.assertCanViewProject(id);
        projectRepository.deleteById(id);
        log.info("[Project] Permanently deleted id={}", id);
    }

    @Transactional
    public void permanentDeleteAll() {
        var accessible = Set.copyOf(projectAccessService.accessibleProjectIds());
        List<Project> trash = projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc().stream()
                .filter(p -> accessible.contains(p.getId()))
                .toList();
        trash.forEach(p -> auditLogService.log("PROJECT.PERMANENT_DELETE", "PROJECT",
                p.getId().toString(), p.getName(), "bulk=all"));
        projectRepository.deleteAll(trash);
        log.info("[Project] Permanently deleted entire trash count={}", trash.size());
    }

    @Transactional
    public void permanentDeleteSelected(List<Long> ids) {
        ids.forEach(id -> {
            projectAccessService.assertCanViewProject(id);
            projectRepository.findById(id).ifPresent(p -> {
                auditLogService.log("PROJECT.PERMANENT_DELETE", "PROJECT",
                        id.toString(), p.getName(), "bulk=selected");
                projectRepository.deleteById(id);
                log.info("[Project] Permanently deleted selected id={}", id);
            });
        });
    }

    @Transactional
    public void restoreSelected(List<Long> ids) {
        ids.forEach(id -> {
            projectAccessService.assertCanViewProject(id);
            projectRepository.findById(id).ifPresent(p -> {
                p.restore();
                projectRepository.save(p);
                auditLogService.log("PROJECT.RESTORE", "PROJECT",
                        id.toString(), p.getName(), "bulk=selected");
                log.info("[Project] Restored selected id={}", id);
            });
        });
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private static final DateTimeFormatter IMPORT_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private ProjectSummaryDto toSummary(Project project) {
        String importedAt = project.getImportedAt() != null
                ? project.getImportedAt().format(IMPORT_FMT)
                : null;

        // Build the display string: "owner/repo#latestBranch" when available
        String githubDisplayRepo = project.getGithubRepo() != null
                ? project.getGithubRepo()
                  + (project.getLatestBranch() != null ? "#" + project.getLatestBranch() : "")
                : null;
        String vcsProvider = project.getVcsProvider() != null
                ? project.getVcsProvider().name()
                : null;

        // Get the most recent scan regardless of status so we can display in-progress and
        // failed states on the project card (not just COMPLETED scans).
        var latestScanOpt = scanResultRepository.findLatestByProjectId(project.getId());

        // No scan at all → truly unsaved / zombie project
        if (latestScanOpt.isEmpty()) {
            return ProjectSummaryDto.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .version("-")
                    .lastScanned("-")
                    .githubRepo(githubDisplayRepo)
                    .vcsProvider(vcsProvider)
                    .importedAt(importedAt)
                    .projectUuid(project.getProjectUuid())
                    .scanStatus(null)
                    .build();
        }

        var latestScan = latestScanOpt.get();
        var status = latestScan.getStatus();

        // For completed scans: aggregate full security/license data
        if (status == com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED) {
            int[] sec = aggregateSecurity(latestScan);
            int[] lic = aggregateLicense(latestScan);
            String lastScanned = latestScan.getScannedAt() != null
                    ? latestScan.getScannedAt().toLocalDate().toString().replace("-", ".")
                    : "-";
            return ProjectSummaryDto.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .version(latestScan.getVersion())
                    .lastScanned(lastScanned)
                    .securityCritical(sec[0]).securityHigh(sec[1])
                    .securityMedium(sec[2]).securityLow(sec[3]).securityUnscored(sec[4])
                    .licenseCritical(lic[0]).licenseHigh(lic[1])
                    .licenseMedium(lic[2]).licenseLow(lic[3])
                    .githubRepo(githubDisplayRepo)
                    .vcsProvider(vcsProvider)
                    .importedAt(importedAt)
                    .projectUuid(project.getProjectUuid())
                    .scanStatus(status.name())
                    .build();
        }

        // For in-progress (SCANNING / ANALYZING) or FAILED scans: show the state without
        // risk counts so the UI can render an appropriate indicator.
        return ProjectSummaryDto.builder()
                .id(project.getId())
                .name(project.getName())
                .version(latestScan.getVersion() != null ? latestScan.getVersion() : "-")
                .lastScanned("-")
                .githubRepo(githubDisplayRepo)
                .vcsProvider(vcsProvider)
                .importedAt(importedAt)
                .projectUuid(project.getProjectUuid())
                .scanStatus(status.name())
                .build();
    }

    private int[] aggregateSecurity(com.salkcoding.oswl.domain.entity.ScanResult scan) {
        int critical = 0, high = 0, medium = 0, low = 0, none = 0;
        for (var comp : scan.getComponents()) {
            for (var cve : comp.getLibrary().getCves()) {
                if (cve.getSeverity() == null) continue;
                switch (cve.getSeverity()) {
                    case CRITICAL -> critical++;
                    case HIGH     -> high++;
                    case MEDIUM   -> medium++;
                    case LOW      -> low++;
                    case NONE     -> none++;
                    default       -> {}
                }
            }
        }
        return new int[]{critical, high, medium, low, none};
    }

    private int[] aggregateLicense(com.salkcoding.oswl.domain.entity.ScanResult scan) {
        int critical = 0, high = 0, unknown = 0, low = 0;
        for (var comp : scan.getComponents()) {
            switch (comp.getLibrary().getLicenseStatus()) {
                case RESTRICTED -> critical++;
                case CAUTION      -> high++;
                case UNKNOWN   -> unknown++;
                default        -> low++;
            }
        }
        return new int[]{critical, high, unknown, low};
    }

    @Transactional
    public void updateDeploymentProfile(Long projectId, DeploymentProfile profile) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        project.updateDeploymentProfile(profile);
        projectRepository.save(project);
        auditLogService.log("PROJECT.DEPLOYMENT_PROFILE", "PROJECT",
                String.valueOf(projectId), project.getName(), profile.name());
    }

    private static final DateTimeFormatter DELETED_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private TrashProjectDto toTrash(Project project) {
        long daysSince = ChronoUnit.DAYS.between(
                project.getDeletedAt().toLocalDate(), LocalDate.now());
        int daysLeft = (int) Math.max(0, 30 - daysSince);
        String urgency = daysLeft <= 7 ? "red" : daysLeft <= 15 ? "orange" : "yellow";
        return TrashProjectDto.builder()
                .id(project.getId())
                .name(project.getName())
                .deletedAt(project.getDeletedAt().format(DELETED_FMT))
                .daysLeft(daysLeft)
                .urgencyColor(urgency)
                .build();
    }
}
