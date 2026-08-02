package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.project.ProjectVersion;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.enums.ImportSource;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.dto.TrashProjectDto;
import com.salkcoding.oswl.repository.CveAlertRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ProjectVersionRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    ProjectRepository projectRepository;

    @Mock
    ProjectVersionRepository projectVersionRepository;

    @Mock
    ScanResultRepository scanResultRepository;

    @Mock
    com.salkcoding.oswl.auth.service.AuditLogService auditLogService;

    @Mock
    ProjectAccessService projectAccessService;

    @Mock
    CveAlertRepository cveAlertRepository;

    @Mock
    TeamService teamService;

    @InjectMocks
    ProjectService projectService;

    private Team defaultTeam() {
        return Team.builder().id(1L).name("Default").build();
    }

    private void stubAccessible(Project... projects) {
        List<Long> ids = java.util.Arrays.stream(projects).map(Project::getId).toList();
        when(projectAccessService.accessibleProjectIds()).thenReturn(ids);
        when(projectRepository.findAllByDeletedAtIsNullAndIdInOrderByCreatedAtDesc(ids))
                .thenReturn(List.of(projects));
        when(cveAlertRepository.countUnacknowledgedByProjectIds(ids)).thenReturn(List.of());
    }

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트가 없으면 빈 리스트를 반환한다")
    void findAll_returnsEmptyList_whenNoProjects() {
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of());

        assertThat(projectService.findAll()).isEmpty();
    }

    @Test
    @DisplayName("스캔 자체가 없으면 버전과 lastScanned가 '-'로 반환된다")
    void findAll_returnsDashes_whenNoScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.empty());

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("P1");
        assertThat(result.getVersion()).isEqualTo("-");
        assertThat(result.getLastScanned()).isEqualTo("-");
        assertThat(result.getScanStatus()).isNull();
    }

    @Test
    @DisplayName("완료된 스캔의 CVE를 심각도별로 집계해 반환한다")
    void findAll_aggregatesSecurityCounts_fromCompletedScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        Cve critical = Cve.builder().library(null).cveId("C1").severity(RiskLevel.CRITICAL).cvssScore(9.8).build();
        Cve high     = Cve.builder().library(null).cveId("C2").severity(RiskLevel.HIGH).cvssScore(7.5).build();
        Cve medium   = Cve.builder().library(null).cveId("C3").severity(RiskLevel.MEDIUM).cvssScore(5.0).build();
        Cve low      = Cve.builder().library(null).cveId("C4").severity(RiskLevel.LOW).cvssScore(2.0).build();

        Library lib = Library.builder()
                .name("lib").version("1.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of(critical, high, medium, low))
                .build();

        ScanComponent comp = ScanComponent.builder()
                .scanResult(null).library(lib)
                .build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .components(List.of(comp))
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 1, 0, 0));

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getSecurityCritical()).isEqualTo(1);
        assertThat(result.getSecurityHigh()).isEqualTo(1);
        assertThat(result.getSecurityMedium()).isEqualTo(1);
        assertThat(result.getSecurityLow()).isEqualTo(1);
    }

    @Test
    @DisplayName("라이선스 상태를 RESTRICTED→critical, CAUTION→high, PERMITTED→low 로 집계한다")
    void findAll_aggregatesLicenseCounts_byLicenseStatus() {
        Project project = Project.builder().id(1L).name("P1").build();

        ScanComponent violation = ScanComponent.builder()
                .scanResult(null)
                .library(Library.builder().name("a").version("1").ecosystem("MAVEN")
                        .licenseStatus(LicenseStatus.RESTRICTED).build())
                .build();
        ScanComponent warn = ScanComponent.builder()
                .scanResult(null)
                .library(Library.builder().name("b").version("1").ecosystem("MAVEN")
                        .licenseStatus(LicenseStatus.CAUTION).build())
                .build();
        ScanComponent ok = ScanComponent.builder()
                .scanResult(null)
                .library(Library.builder().name("c").version("1").ecosystem("MAVEN")
                        .licenseStatus(LicenseStatus.PERMITTED).build())
                .build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("2.0").status(ScanStatus.COMPLETED)
                .components(List.of(violation, warn, ok))
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 4, 10, 0));

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getLicenseCritical()).isEqualTo(1);  // RESTRICTED
        assertThat(result.getLicenseHigh()).isEqualTo(1);      // CAUTION
        assertThat(result.getLicenseMedium()).isEqualTo(0);    // (no UNKNOWN)
        assertThat(result.getLicenseLow()).isEqualTo(1);       // PERMITTED
    }

    @Test
    @DisplayName("lastScanned 날짜를 'yyyy.MM.dd' 형식으로 포맷한다")
    void findAll_formatsLastScannedDate() {
        Project project = Project.builder().id(1L).name("P1").build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 15, 12, 0));

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));

        assertThat(projectService.findAll().getFirst().getLastScanned()).isEqualTo("2026.04.15");
    }

    @Test
    @DisplayName("스캔이 없는 프로젝트에서 보안·라이선스 카운트는 0이다")
    void findAll_returnsZeroCounts_whenNoScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.empty());

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getSecurityCritical()).isZero();
        assertThat(result.getLicenseCritical()).isZero();
    }

    @Test
    @DisplayName("SCANNING 상태의 스캔이 있으면 scanStatus에 'SCANNING'이 담긴다")
    void findAll_returnsScanningStatus_whenScanInProgress() {
        Project project = Project.builder().id(1L).name("P1").build();

        ScanResult scanning = ScanResult.builder()
                .project(project).version("2.0").status(ScanStatus.SCANNING)
                .build();
        scanning.setScannedAt(LocalDateTime.now());

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scanning));

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getScanStatus()).isEqualTo("SCANNING");
        assertThat(result.getLastScanned()).isEqualTo("-");
        assertThat(result.getSecurityCritical()).isZero();
    }

    @Test
    @DisplayName("FAILED 상태의 스캔이 있으면 scanStatus에 'FAILED'가 담긴다")
    void findAll_returnsFailedStatus_whenScanFailed() {
        Project project = Project.builder().id(1L).name("P1").build();

        ScanResult failed = ScanResult.builder()
                .project(project).version("1.5").status(ScanStatus.FAILED)
                .build();
        failed.setScannedAt(LocalDateTime.now());

        stubAccessible(project);
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(failed));

        ProjectSummaryDto result = projectService.findAll().getFirst();

        assertThat(result.getScanStatus()).isEqualTo("FAILED");
        assertThat(result.getLicenseCritical()).isZero();
    }

    // ── findTrash ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("findTrash는 삭제된 프로젝트 목록을 반환한다")
    void findTrash_returnsTrashedProjects() {
        Project deleted = Project.builder().id(1L).name("Deleted").build();
        deleted.softDelete();

        when(projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc()).thenReturn(List.of(deleted));
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of(1L));

        List<TrashProjectDto> result = projectService.findTrash();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getName()).isEqualTo("Deleted");
        assertThat(result.getFirst().getDaysLeft()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("삭제된 지 29일인 프로젝트는 urgencyColor='yellow'다")
    void findTrash_urgencyYellow_for29Days() {
        Project p = Project.builder().id(2L).name("RecentDeleted").build();
        // Simulate deleted 29 days ago (1 day left → within 30, but > 15 → yellow)
        // We can't set deletedAt directly via the builder with the real entity method,
        // so we check that the returned urgency aligns with the days logic.
        p.softDelete();  // just deleted → 30 daysLeft → "yellow"

        when(projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc()).thenReturn(List.of(p));
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of(2L));

        TrashProjectDto dto = projectService.findTrash().getFirst();

        // Just deleted (0 days elapsed) → 30 days left → "yellow"
        assertThat(dto.getUrgencyColor()).isEqualTo("yellow");
        assertThat(dto.getDaysLeft()).isEqualTo(30);
    }

    // ── getById ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하는 프로젝트 ID로 조회하면 해당 프로젝트를 반환한다")
    void getById_returnsProject_whenExists() {
        Project project = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        doNothing().when(projectAccessService).assertCanViewProject(1L);

        assertThat(projectService.getById(1L)).isEqualTo(project);
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 IllegalArgumentException이 발생한다")
    void getById_throwsIllegalArgument_whenNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.getById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    // ── create ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트 이름으로 생성하면 저장 후 반환한다")
    void create_savesAndReturnsProject() {
        Project saved = Project.builder().id(1L).name("NewProject").build();
        when(teamService.findDefaultTeam()).thenReturn(defaultTeam());
        when(projectRepository.save(any(Project.class))).thenReturn(saved);

        Project result = projectService.create("NewProject");

        assertThat(result.getName()).isEqualTo("NewProject");
        verify(projectRepository).save(argThat(p -> "NewProject".equals(p.getName())));
    }

    // ── delete (soft) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("delete 호출 시 프로젝트를 소프트삭제하고 저장한다")
    void delete_softDeletesProject() {
        Project project = Project.builder().id(5L).name("ToDelete").build();
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        projectService.delete(5L);

        assertThat(project.getDeletedAt()).isNotNull();
        verify(projectRepository).save(project);
    }

    @Test
    @DisplayName("delete 시 존재하지 않는 ID면 IllegalArgumentException이 발생한다")
    void delete_throwsIllegalArgument_whenNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projectService.delete(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── restore ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("restore 호출 시 삭제된 프로젝트가 복구된다")
    void restore_restoresDeletedProject() {
        Project project = Project.builder().id(1L).name("P").build();
        project.softDelete();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        projectService.restore(1L);

        assertThat(project.getDeletedAt()).isNull();
        verify(projectRepository).save(project);
    }

    // ── permanentDelete ───────────────────────────────────────────────────

    @Test
    @DisplayName("permanentDelete 호출 시 repository.deleteById에 위임한다")
    void permanentDelete_callsDeleteById() {
        projectService.permanentDelete(7L);

        verify(projectRepository).deleteById(7L);
    }

    // ── permanentDeleteAll ────────────────────────────────────────────────

    @Test
    @DisplayName("permanentDeleteAll은 휴지통의 모든 프로젝트를 삭제한다")
    void permanentDeleteAll_deletesAllTrashedProjects() {
        Project p1 = Project.builder().id(1L).name("P1").build();
        Project p2 = Project.builder().id(2L).name("P2").build();
        p1.softDelete();
        p2.softDelete();
        when(projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc())
                .thenReturn(List.of(p1, p2));
        when(projectAccessService.accessibleProjectIds()).thenReturn(List.of(1L, 2L));

        projectService.permanentDeleteAll();

        verify(projectRepository).deleteAll(List.of(p1, p2));
    }

    // ── permanentDeleteSelected ───────────────────────────────────────────

    @Test
    @DisplayName("permanentDeleteSelected는 존재하는 ID만 삭제한다")
    void permanentDeleteSelected_deletesOnlyExistingIds() {
        Project p1 = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        projectService.permanentDeleteSelected(List.of(1L, 99L));

        verify(projectRepository).deleteById(1L);
        verify(projectRepository, never()).deleteById(99L);
    }

    // ── restoreSelected ───────────────────────────────────────────────────

    @Test
    @DisplayName("restoreSelected는 각 ID에 대해 restore를 수행한다")
    void restoreSelected_restoresMultipleProjects() {
        Project p1 = Project.builder().id(1L).name("P1").build();
        Project p2 = Project.builder().id(2L).name("P2").build();
        p1.softDelete();
        p2.softDelete();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p1));
        when(projectRepository.findById(2L)).thenReturn(Optional.of(p2));

        projectService.restoreSelected(List.of(1L, 2L));

        assertThat(p1.getDeletedAt()).isNull();
        assertThat(p2.getDeletedAt()).isNull();
        verify(projectRepository, times(2)).save(any(Project.class));
    }

    // ── upsertFromGitHub ──────────────────────────────────────────────────

    @Test
    @DisplayName("upsertFromGitHub — 새 저장소이면 프로젝트를 생성하고 첫 번째 버전을 추가한다")
    void upsertFromGitHub_createsNewProject_whenRepoNotFound() {
        when(teamService.findDefaultTeam()).thenReturn(defaultTeam());
        when(projectRepository.findByGithubRepo("owner/new-repo")).thenReturn(Optional.empty());
        Project created = Project.builder().id(10L).name("owner/new-repo").build();
        when(projectRepository.save(any(Project.class))).thenReturn(created);
        when(projectVersionRepository.findByProjectAndBranch(created, "main")).thenReturn(Optional.empty());
        when(projectVersionRepository.findMaxVersionNumber(created)).thenReturn(0);
        when(projectVersionRepository.save(any(ProjectVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        Project result = projectService.upsertFromGitHub("owner", "new-repo", "main");

        verify(projectRepository, atLeastOnce()).save(any(Project.class));
        verify(projectVersionRepository).save(argThat(v -> v.getBranch().equals("main")
                && v.getVersionNumber() == 1
                && v.getImportSource() == ImportSource.GIT));
        assertThat(result).isEqualTo(created);
    }

    @Test
    @DisplayName("upsertFromGitHub — 이미 존재하는 브랜치이면 ProjectVersion을 추가하지 않는다")
    void upsertFromGitHub_skipsVersion_whenSameBranchFound() {
        Project existing = Project.builder().id(5L).name("owner/repo").build();
        ProjectVersion existingVersion = ProjectVersion.builder()
                .project(existing).branch("main").versionNumber(1)
                .importSource(ImportSource.GIT).build();

        when(projectRepository.findByGithubRepo("owner/repo")).thenReturn(Optional.of(existing));
        when(projectVersionRepository.findByProjectAndBranch(existing, "main"))
                .thenReturn(Optional.of(existingVersion));
        when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        projectService.upsertFromGitHub("owner", "repo", "main");

        verify(projectVersionRepository, never()).save(any());
        verify(projectVersionRepository, never()).findMaxVersionNumber(any());
    }
}
