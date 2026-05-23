package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.BulkStatusRequest;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityCenterService 단위 테스트")
class SecurityCenterServiceTest {

    @Mock ProjectRepository       projectRepository;
    @Mock ScanResultRepository    scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock LibraryRepository       libraryRepository;
    @Mock AuditLogService         auditLogService;

    @InjectMocks
    SecurityCenterService securityCenterService;

    // ── populateModel ────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsException_whenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> securityCenterService.populateModel(99L, null, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("완료된 스캔이 없으면 빈 요약 데이터가 모델에 담긴다")
    void populateModel_addsEmptySummary_whenNoCompletedScans() {
        Project project = Project.builder().id(1L).name("MyProject").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.empty());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(1L, null, model);

        assertThat(model.getAttribute("projectName")).isEqualTo("MyProject");
        assertThat(model.getAttribute("latestScanStatus")).isEqualTo("NONE");
        assertThat(model.getAttribute("latestScanId")).isNull();
        assertThat(model.getAttribute("securityCritical")).isEqualTo(0);
        assertThat(model.getAttribute("securityHigh")).isEqualTo(0);
        assertThat(model.getAttribute("components")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("프로젝트 이름과 ID가 모델에 설정된다")
    void populateModel_setsProjectAttributes() {
        Project project = Project.builder().id(2L).name("Test-Project").build();
        when(projectRepository.findById(2L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(2L)).thenReturn(List.of());
        when(scanResultRepository.findLatestByProjectId(2L)).thenReturn(Optional.empty());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(2L, null, model);

        assertThat(model.getAttribute("projectId")).isEqualTo(2L);
        assertThat(model.getAttribute("projectName")).isEqualTo("Test-Project");
    }

    @Test
    @DisplayName("스캔이 있으면 currentScanId와 projectVersion이 첫 번째 스캔으로 설정된다")
    void populateModel_withScans_selectsFirstScan() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of());
        when(scanComponentRepository.findByScanResultId(10L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(1L, null, model);

        assertThat(model.getAttribute("currentScanId")).isEqualTo(10L);
        assertThat(model.getAttribute("projectVersion")).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("scanId 파라미터가 있으면 해당 스캔이 선택된다")
    void populateModel_specificScanId_selectsMatchingScan() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan1 = buildScan(10L, "1.0");
        ScanResult scan2 = buildScan(20L, "2.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan1, scan2));
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan1));
        when(libraryRepository.findByScanResultIdWithCves(20L)).thenReturn(List.of());
        when(scanComponentRepository.findByScanResultId(20L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(1L, 20L, model);

        assertThat(model.getAttribute("currentScanId")).isEqualTo(20L);
        assertThat(model.getAttribute("projectVersion")).isEqualTo("2.0");
    }

    @Test
    @DisplayName("라이브러리 CVE 심각도가 securityCritical/High 합산에 반영된다")
    void populateModel_cveSeverityCounts_areAggregated() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));

        Cve cve1 = Cve.builder().cveId("CVE-2021-1").severity(RiskLevel.CRITICAL).build();
        Cve cve2 = Cve.builder().cveId("CVE-2021-2").severity(RiskLevel.CRITICAL).build();
        Cve cve3 = Cve.builder().cveId("CVE-2021-3").severity(RiskLevel.HIGH).build();
        Library lib = Library.builder()
                .id(100L).name("log4j").version("2.14.1").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of(cve1, cve2, cve3))
                .build();

        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));
        when(scanComponentRepository.findByScanResultId(10L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryIds(List.of(100L))).thenReturn(List.of());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(1L, null, model);

        assertThat(model.getAttribute("securityCritical")).isEqualTo(2);
        assertThat(model.getAttribute("securityHigh")).isEqualTo(1);
        assertThat(model.getAttribute("securityMedium")).isEqualTo(0);
    }

    @Test
    @DisplayName("RESTRICTED 라이선스 라이브러리는 licenseCritical 카운트에 집계된다")
    void populateModel_restrictedLicense_incrementsLicenseCritical() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));
        when(scanResultRepository.findLatestByProjectId(1L)).thenReturn(Optional.of(scan));

        Library lib = Library.builder()
                .id(200L).name("gpl-lib").version("1.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.RESTRICTED)
                .cves(List.of())
                .build();

        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));
        when(scanComponentRepository.findByScanResultId(10L)).thenReturn(List.of());
        when(scanComponentRepository.countDistinctProjectsByLibraryIds(List.of(200L))).thenReturn(List.of());

        Model model = new ConcurrentModel();
        securityCenterService.populateModel(1L, null, model);

        assertThat(model.getAttribute("licenseCritical")).isEqualTo(1);
        assertThat(model.getAttribute("licenseHigh")).isEqualTo(0);
    }

    // ── bulkUpdateStatus ─────────────────────────────────────────────────

    @Test
    @DisplayName("bulkUpdateStatus: reviewed=true 설정 시 markReviewed가 호출된다")
    void bulkUpdateStatus_marksReviewed() {
        ScanComponent sc1 = mock(ScanComponent.class);
        ScanComponent sc2 = mock(ScanComponent.class);
        when(scanComponentRepository.findAllByIdInAndProjectId(List.of(1L, 2L), 10L))
                .thenReturn(List.of(sc1, sc2));

        securityCenterService.bulkUpdateStatus(10L, new BulkStatusRequest(List.of(1L, 2L), true, null));

        verify(sc1).markReviewed(true);
        verify(sc2).markReviewed(true);
        verify(sc1, never()).markIgnored(anyBoolean());
        verify(sc2, never()).markIgnored(anyBoolean());
    }

    @Test
    @DisplayName("bulkUpdateStatus: ignored=false 설정 시 markIgnored가 호출된다")
    void bulkUpdateStatus_marksIgnored() {
        ScanComponent sc = mock(ScanComponent.class);
        when(scanComponentRepository.findAllByIdInAndProjectId(List.of(5L), 10L))
                .thenReturn(List.of(sc));

        securityCenterService.bulkUpdateStatus(10L, new BulkStatusRequest(List.of(5L), null, false));

        verify(sc, never()).markReviewed(anyBoolean());
        verify(sc).markIgnored(false);
    }

    @Test
    @DisplayName("bulkUpdateStatus: reviewed와 ignored 둘 다 설정 시 모두 호출된다")
    void bulkUpdateStatus_marksBoth() {
        ScanComponent sc = mock(ScanComponent.class);
        when(scanComponentRepository.findAllByIdInAndProjectId(List.of(3L), 10L))
                .thenReturn(List.of(sc));

        securityCenterService.bulkUpdateStatus(10L, new BulkStatusRequest(List.of(3L), true, true));

        verify(sc).markReviewed(true);
        verify(sc).markIgnored(true);
    }

    @Test
    @DisplayName("bulkUpdateStatus: 매칭 컴포넌트 없으면 auditLog만 호출된다")
    void bulkUpdateStatus_noMatchingComponents_onlyAuditLog() {
        when(scanComponentRepository.findAllByIdInAndProjectId(anyList(), anyLong()))
                .thenReturn(List.of());

        securityCenterService.bulkUpdateStatus(10L, new BulkStatusRequest(List.of(99L), true, true));

        verify(auditLogService).log(anyString(), anyString(), anyString(), isNull(), anyString());
    }

    // ── buildExportCsv ───────────────────────────────────────────────────

    @Test
    @DisplayName("buildExportCsv: 프로젝트 없으면 throws")
    void buildExportCsv_projectNotFound_throws() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> securityCenterService.buildExportCsv(99L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("buildExportCsv: 스캔 없으면 헤더만 반환")
    void buildExportCsv_noScans_returnsHeaderOnly() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(Project.builder().id(1L).name("P").build()));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        byte[] csv = securityCenterService.buildExportCsv(1L, null);
        String result = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(result).startsWith("Component Name,Version");
        // 헤더 이후 데이터 행 없음
        assertThat(result.split("\n")).hasSize(1);
    }

    @Test
    @DisplayName("buildExportCsv: 쉼표 포함 이름은 따옴표로 감싸진다")
    void buildExportCsv_nameWithComma_isQuoted() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Library lib = Library.builder()
                .id(100L).name("some,lib").version("1.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .licenseName("MIT")
                .cves(List.of())
                .build();

        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.isReviewed()).thenReturn(false);
        when(sc.isIgnored()).thenReturn(false);
        when(sc.isDeferred()).thenReturn(false);
        when(sc.getDeferralReason()).thenReturn(null);
        when(scanComponentRepository.findByScanResultId(10L)).thenReturn(List.of(sc));

        byte[] csv = securityCenterService.buildExportCsv(1L, null);
        String result = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(result).contains("\"some,lib\"");
    }

    @Test
    @DisplayName("buildExportCsv: 컴포넌트 데이터가 포함된다")
    void buildExportCsv_withComponents_includesData() {
        Project project = Project.builder().id(1L).name("Demo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ScanResult scan = buildScan(10L, "1.0");
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Cve cve = Cve.builder().cveId("CVE-2021-1").severity(RiskLevel.CRITICAL).build();
        Library lib = Library.builder()
                .id(100L).name("spring-core").version("5.3.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .licenseName("Apache-2.0")
                .cves(List.of(cve))
                .build();

        ScanComponent sc = mock(ScanComponent.class);
        when(sc.getLibrary()).thenReturn(lib);
        when(sc.isReviewed()).thenReturn(true);
        when(sc.isIgnored()).thenReturn(false);
        when(sc.isDeferred()).thenReturn(false);
        when(sc.getDeferralReason()).thenReturn(null);
        when(scanComponentRepository.findByScanResultId(10L)).thenReturn(List.of(sc));

        byte[] csv = securityCenterService.buildExportCsv(1L, null);
        String result = new String(csv, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(result).contains("spring-core");
        assertThat(result).contains("5.3.0");
        assertThat(result).contains("Yes"); // reviewed = true
        verify(auditLogService).log(eq("SECURITY_CENTER.EXPORT"), anyString(), anyString(), anyString(), anyString());
    }

    // ── helper ────────────────────────────────────────────────────────────

    private ScanResult buildScan(Long id, String version) {
        return ScanResult.builder()
                .id(id)
                .version(version)
                .status(ScanStatus.COMPLETED)
                .scannedAt(LocalDateTime.now())
                .build();
    }
}
