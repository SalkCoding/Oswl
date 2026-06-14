package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("RiskTrendService 단위 테스트")
class RiskTrendServiceTest {

    @Mock ProjectRepository    projectRepository;
    @Mock ScanResultRepository scanResultRepository;
    @Mock LibraryRepository    libraryRepository;
    @Mock AiAnalysisService    aiAnalysisService;

    @InjectMocks
    RiskTrendService riskTrendService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(riskTrendService, "trendLimit", 10);
    }

    @Test
    @DisplayName("프로젝트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsException_whenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> riskTrendService.populateModel(99L, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("완료된 스캔이 없으면 빈 차트 데이터가 모델에 담긴다")
    void populateModel_addsEmptyChartData_whenNoCompletedScans() {
        Project project = Project.builder().id(1L).name("TestProject").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findRecentCompleted(1L, 10)).thenReturn(List.of());
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        riskTrendService.populateModel(1L, model);

        assertThat(model.getAttribute("projectName")).isEqualTo("TestProject");
        assertThat(model.getAttribute("securityIssues")).isEqualTo(0);
        assertThat(model.getAttribute("licenseIssues")).isEqualTo(0);
        assertThat(model.getAttribute("securityDelta")).isEqualTo(0);
        assertThat((List<?>) model.getAttribute("chartVersions")).isEmpty();
        assertThat((List<?>) model.getAttribute("chartSecCritical")).isEmpty();
    }

    @Test
    @DisplayName("완료된 스캔이 있으면 차트 데이터가 올바르게 집계된다")
    void populateModel_aggregatesChartData_withCompletedScans() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Cve crit = Cve.builder().cveId("CVE-1").severity(RiskLevel.CRITICAL).cvssScore(9.8).library(null).build();
        Cve high = Cve.builder().cveId("CVE-2").severity(RiskLevel.HIGH).cvssScore(7.5).library(null).build();

        Library lib = Library.builder()
                .name("log4j").version("2.14.0").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.RESTRICTED)
                .cves(List.of(crit, high))
                .build();

        ScanResult scan = ScanResult.builder()
                .id(10L).project(project).version("1.0").status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findRecentCompleted(1L, 10)).thenReturn(List.of(scan));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));
        when(libraryRepository.findByScanResultIdWithCves(10L)).thenReturn(List.of(lib));

        Model model = new ConcurrentModel();
        riskTrendService.populateModel(1L, model);

        assertThat(model.getAttribute("projectVersion")).isEqualTo("1.0");
        assertThat(model.getAttribute("securityIssues")).isEqualTo(2); // critical + high
        assertThat(model.getAttribute("licenseIssues")).isEqualTo(1);  // restricted

        @SuppressWarnings("unchecked")
        List<String> versions = (List<String>) model.getAttribute("chartVersions");
        assertThat(versions).containsExactly("1.0");

        @SuppressWarnings("unchecked")
        List<Integer> criticals = (List<Integer>) model.getAttribute("chartSecCritical");
        assertThat(criticals).containsExactly(1);
    }

    @Test
    @DisplayName("스캔이 두 개 이상이면 delta가 계산된다")
    void populateModel_computesDelta_withTwoScans() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Cve c1 = Cve.builder().cveId("CVE-A").severity(RiskLevel.CRITICAL).library(null).build();
        Library lib1 = Library.builder()
                .name("libA").version("1").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of(c1)).build();

        Cve c2 = Cve.builder().cveId("CVE-B").severity(RiskLevel.HIGH).library(null).build();
        Cve c3 = Cve.builder().cveId("CVE-C").severity(RiskLevel.MEDIUM).library(null).build();
        Library lib2 = Library.builder()
                .name("libB").version("2").ecosystem("MAVEN")
                .licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of(c2, c3)).build();

        ScanResult older = ScanResult.builder().id(1L).project(project).version("1.0").status(ScanStatus.COMPLETED).build();
        older.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult newer = ScanResult.builder().id(2L).project(project).version("2.0").status(ScanStatus.COMPLETED).build();
        newer.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        // findRecentCompleted returns newest first
        when(scanResultRepository.findRecentCompleted(1L, 10)).thenReturn(List.of(newer, older));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(newer, older));
        when(libraryRepository.findByScanResultIdWithCves(2L)).thenReturn(List.of(lib2)); // newer has 2 issues
        when(libraryRepository.findByScanResultIdWithCves(1L)).thenReturn(List.of(lib1)); // older has 1 issue

        Model model = new ConcurrentModel();
        riskTrendService.populateModel(1L, model);

        // newer=2 issues, older=1 issue → securityDelta=+1
        assertThat(model.getAttribute("securityDelta")).isEqualTo(1);
    }
}
