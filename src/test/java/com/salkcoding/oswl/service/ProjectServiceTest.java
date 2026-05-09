package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.repository.ProjectRepository;
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
    ScanResultRepository scanResultRepository;

    @InjectMocks
    ProjectService projectService;

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트가 없으면 빈 리스트를 반환한다")
    void findAll_returnsEmptyList_whenNoProjects() {
        when(projectRepository.findAll()).thenReturn(List.of());

        assertThat(projectService.findAll()).isEmpty();
    }

    @Test
    @DisplayName("완료된 스캔이 없으면 버전과 lastScanned가 '-'로 반환된다")
    void findAll_returnsDashes_whenNoCompletedScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(scanResultRepository.findFirstByProjectIdAndStatusOrderByScannedAtDesc(1L, ScanStatus.COMPLETED))
                .thenReturn(Optional.empty());

        ProjectSummaryDto result = projectService.findAll().get(0);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("P1");
        assertThat(result.getVersion()).isEqualTo("-");
        assertThat(result.getLastScanned()).isEqualTo("-");
    }

    @Test
    @DisplayName("완료된 스캔의 CVE를 심각도별로 집계해 반환한다")
    void findAll_aggregatesSecurityCounts_fromCompletedScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        Cve critical = Cve.builder().component(null).cveId("C1").severity(RiskLevel.CRITICAL).cvssScore(9.8).build();
        Cve high     = Cve.builder().component(null).cveId("C2").severity(RiskLevel.HIGH).cvssScore(7.5).build();
        Cve medium   = Cve.builder().component(null).cveId("C3").severity(RiskLevel.MEDIUM).cvssScore(5.0).build();
        Cve low      = Cve.builder().component(null).cveId("C4").severity(RiskLevel.LOW).cvssScore(2.0).build();

        OswlComponent comp = OswlComponent.builder()
                .scanResult(null).name("lib").version("1.0")
                .licenseStatus(LicenseStatus.PERMITTED).patchability(Patchability.PATCHABLE)
                .cves(List.of(critical, high, medium, low))
                .build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0.0").status(ScanStatus.COMPLETED)
                .components(List.of(comp))
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 1, 0, 0));

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(scanResultRepository.findFirstByProjectIdAndStatusOrderByScannedAtDesc(1L, ScanStatus.COMPLETED))
                .thenReturn(Optional.of(scan));

        ProjectSummaryDto result = projectService.findAll().get(0);

        assertThat(result.getSecurityCritical()).isEqualTo(1);
        assertThat(result.getSecurityHigh()).isEqualTo(1);
        assertThat(result.getSecurityMedium()).isEqualTo(1);
        assertThat(result.getSecurityLow()).isEqualTo(1);
    }

    @Test
    @DisplayName("라이선스 상태를 VIOLATION→critical, WARN→high, OK→low 로 집계한다")
    void findAll_aggregatesLicenseCounts_byLicenseStatus() {
        Project project = Project.builder().id(1L).name("P1").build();

        OswlComponent violation = OswlComponent.builder()
                .scanResult(null).name("a").version("1")
                .licenseStatus(LicenseStatus.RESTRICTED).patchability(Patchability.UNKNOWN).build();
        OswlComponent warn = OswlComponent.builder()
                .scanResult(null).name("b").version("1")
                .licenseStatus(LicenseStatus.CAUTION).patchability(Patchability.UNKNOWN).build();
        OswlComponent ok = OswlComponent.builder()
                .scanResult(null).name("c").version("1")
                .licenseStatus(LicenseStatus.PERMITTED).patchability(Patchability.UNKNOWN).build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("2.0").status(ScanStatus.COMPLETED)
                .components(List.of(violation, warn, ok))
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 4, 10, 0));

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(scanResultRepository.findFirstByProjectIdAndStatusOrderByScannedAtDesc(1L, ScanStatus.COMPLETED))
                .thenReturn(Optional.of(scan));

        ProjectSummaryDto result = projectService.findAll().get(0);

        assertThat(result.getLicenseCritical()).isEqualTo(1);  // VIOLATION
        assertThat(result.getLicenseHigh()).isEqualTo(1);      // WARN
        assertThat(result.getLicenseMedium()).isEqualTo(0);    // 집계 없음
        assertThat(result.getLicenseLow()).isEqualTo(1);       // OK
    }

    @Test
    @DisplayName("lastScanned 날짜를 'yyyy.MM.dd' 형식으로 포맷한다")
    void findAll_formatsLastScannedDate() {
        Project project = Project.builder().id(1L).name("P1").build();

        ScanResult scan = ScanResult.builder()
                .project(project).version("1.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 4, 15, 12, 0));

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(scanResultRepository.findFirstByProjectIdAndStatusOrderByScannedAtDesc(1L, ScanStatus.COMPLETED))
                .thenReturn(Optional.of(scan));

        assertThat(projectService.findAll().get(0).getLastScanned()).isEqualTo("2026.04.15");
    }

    @Test
    @DisplayName("스캔이 없는 프로젝트에서 보안·라이선스 카운트는 0이다")
    void findAll_returnsZeroCounts_whenNoCompletedScan() {
        Project project = Project.builder().id(1L).name("P1").build();

        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(scanResultRepository.findFirstByProjectIdAndStatusOrderByScannedAtDesc(1L, ScanStatus.COMPLETED))
                .thenReturn(Optional.empty());

        ProjectSummaryDto result = projectService.findAll().get(0);

        assertThat(result.getSecurityCritical()).isZero();
        assertThat(result.getLicenseCritical()).isZero();
    }

    // ── getById ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("존재하는 프로젝트 ID로 조회하면 해당 프로젝트를 반환한다")
    void getById_returnsProject_whenExists() {
        Project project = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

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
        when(projectRepository.save(any(Project.class))).thenReturn(saved);

        Project result = projectService.create("NewProject");

        assertThat(result.getName()).isEqualTo("NewProject");
        verify(projectRepository).save(argThat(p -> "NewProject".equals(p.getName())));
    }

    // ── delete ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete 호출 시 repository.deleteById에 위임한다")
    void delete_delegatesToRepository() {
        projectService.delete(5L);

        verify(projectRepository).deleteById(5L);
    }
}
