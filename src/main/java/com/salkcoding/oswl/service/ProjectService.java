package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectVersion;
import com.salkcoding.oswl.domain.enums.ImportSource;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.dto.TrashProjectDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ProjectVersionRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import static com.salkcoding.oswl.domain.enums.LicenseStatus.*;
import static com.salkcoding.oswl.domain.enums.RiskLevel.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectVersionRepository projectVersionRepository;
    private final ScanResultRepository scanResultRepository;

    @Transactional(readOnly = true)
    public List<ProjectSummaryDto> findAll() {
        return projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TrashProjectDto> findTrash() {
        return projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc().stream()
                .map(this::toTrash)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Project getById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
    }

    @Transactional
    public Project create(String name) {
        Project project = Project.builder().name(name).build();
        Project saved = projectRepository.save(project);
        log.info("[Project] 생성 id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    /**
     * Find-or-create a Project for the given GitHub owner/repo, then upsert a
     * {@link ProjectVersion} for the given branch.
     *
     * <ul>
     *   <li>Same owner/repo + same branch → update {@code lastUpdatedAt} (re-import).</li>
     *   <li>Same owner/repo + new branch → create a new version with the next sequential number.</li>
     *   <li>New owner/repo → create a new Project (UUID auto-generated) and first version.</li>
     * </ul>
     *
     * @return the Project (existing or newly created)
     */
    @Transactional
    public Project upsertFromGitHub(String owner, String repo, String branch) {
        String repoKey = owner + "/" + repo;

        // 1. Find or create the logical project
        Project project = projectRepository.findByGithubRepo(repoKey)
                .orElseGet(() -> projectRepository.save(
                        Project.builder().name(repoKey).build()
                ));

        // 2. Upsert the branch-level version
        projectVersionRepository.findByProjectAndBranch(project, branch)
                .ifPresentOrElse(
                        version -> {
                            version.touch();
                            projectVersionRepository.save(version);
                        },
                        () -> {
                            int nextNum = projectVersionRepository.findMaxVersionNumber(project) + 1;
                            projectVersionRepository.save(ProjectVersion.builder()
                                    .project(project)
                                    .branch(branch)
                                    .versionNumber(nextNum)
                                    .importSource(ImportSource.GIT)
                                    .build());
                        }
                );

        // 3. Update denormalized fields on the project
        project.markGithubImport(owner, repo, branch);
        Project saved = projectRepository.save(project);
        log.info("[Project] GitHub import projectId={} repo={} branch={}", saved.getId(), repoKey, branch);
        return saved;
    }

    /** Soft-delete: moves the project to trash. */
    @Transactional
    public void delete(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        project.softDelete();
        projectRepository.save(project);
        log.info("[Project] 소프트삭제 id={}", id);
    }

    @Transactional
    public void restore(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
        project.restore();
        projectRepository.save(project);
        log.info("[Project] 복구 id={}", id);
    }

    @Transactional
    public void permanentDelete(Long id) {
        projectRepository.deleteById(id);
        log.info("[Project] 영구삭제 id={}", id);
    }

    @Transactional
    public void permanentDeleteAll() {
        List<Project> trash = projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc();
        projectRepository.deleteAll(trash);
        log.info("[Project] 휴지통 전체 영구삭제 count={}", trash.size());
    }

    @Transactional
    public void permanentDeleteSelected(List<Long> ids) {
        ids.forEach(id -> {
            if (projectRepository.existsById(id)) {
                projectRepository.deleteById(id);
                log.info("[Project] 선택 영구삭제 id={}", id);
            }
        });
    }

    @Transactional
    public void restoreSelected(List<Long> ids) {
        ids.forEach(id -> projectRepository.findById(id).ifPresent(p -> {
            p.restore();
            projectRepository.save(p);
            log.info("[Project] 선택 복구 id={}", id);
        }));
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

        // Extract aggregate values from the latest completed scan
        return scanResultRepository
                .findFirstByProjectIdAndStatusOrderByScannedAtDesc(
                        project.getId(),
                        com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED)
                .map(scan -> {
                    int[] sec = aggregateSecurity(scan);
                    int[] lic = aggregateLicense(scan);
                    String lastScanned = scan.getScannedAt() != null
                            ? scan.getScannedAt().toLocalDate().toString().replace("-", ".")
                            : "-";
                    return ProjectSummaryDto.builder()
                            .id(project.getId())
                            .name(project.getName())
                            .version(scan.getVersion())
                            .lastScanned(lastScanned)
                            .securityCritical(sec[0]).securityHigh(sec[1])
                            .securityMedium(sec[2]).securityLow(sec[3]).securityUnscored(sec[4])
                            .licenseCritical(lic[0]).licenseHigh(lic[1])
                            .licenseMedium(lic[2]).licenseLow(lic[3])
                            .githubRepo(githubDisplayRepo)
                            .importedAt(importedAt)
                            .projectUuid(project.getProjectUuid())
                            .build();
                })
                .orElseGet(() -> ProjectSummaryDto.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .version("-")
                        .lastScanned("-")
                        .githubRepo(githubDisplayRepo)
                        .importedAt(importedAt)
                        .projectUuid(project.getProjectUuid())
                        .build());
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
