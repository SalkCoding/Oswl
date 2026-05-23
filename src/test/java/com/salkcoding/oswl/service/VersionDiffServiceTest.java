package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionDiffRowDto.ChangeType;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VersionDiffService 단위 테스트")
class VersionDiffServiceTest {

    @Mock ProjectRepository       projectRepository;
    @Mock ScanResultRepository    scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock AiAnalysisService       aiAnalysisService;

    @InjectMocks
    VersionDiffService versionDiffService;

    @Test
    @DisplayName("프로젝트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsException_whenProjectNotFound() {
        when(projectRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> versionDiffService.populateModel(99L, null, null, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("완료된 스캔이 없으면 diffRows가 빈 리스트다")
    void populateModel_emptyDiffRows_whenNoCompletedScans() {
        Project project = Project.builder().id(1L).name("Proj").build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, null, null, model);

        assertThat((List<?>) model.getAttribute("diffRows")).isEmpty();
        assertThat(model.getAttribute("totalCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("from과 to가 같은 스캔이면 diffRows가 빈 리스트다")
    void populateModel_emptyDiffRows_whenFromAndToSameScan() {
        Project project = Project.builder().id(1L).name("Proj").build();
        ScanResult scan = ScanResult.builder().id(10L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        scan.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(scan));

        Model model = new ConcurrentModel();
        // pass same scan id for both from and to
        versionDiffService.populateModel(1L, 10L, 10L, model);

        assertThat((List<?>) model.getAttribute("diffRows")).isEmpty();
        assertThat(model.getAttribute("totalCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("새로 추가된 컴포넌트는 ADDED 타입으로 집계된다")
    void populateModel_detectsAddedComponents() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Library libFrom = Library.builder().name("existing-lib").version("1.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();
        Library libTo = Library.builder().name("new-lib").version("2.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();

        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        ScanComponent fromComp = ScanComponent.builder().id(100L).library(libFrom)
                .scanResult(from).build();
        ScanComponent toComp1 = ScanComponent.builder().id(200L).library(libFrom)
                .scanResult(to).build();
        ScanComponent toComp2 = ScanComponent.builder().id(201L).library(libTo)
                .scanResult(to).build();

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanComponentRepository.findByScanResultId(1L)).thenReturn(List.of(fromComp));
        when(scanComponentRepository.findByScanResultId(2L)).thenReturn(List.of(toComp1, toComp2));

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("addedCount")).isEqualTo(1);
        assertThat(model.getAttribute("removedCount")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<VersionDiffRowDto> rows = (List<VersionDiffRowDto>) model.getAttribute("diffRows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo(ChangeType.ADDED);
        assertThat(rows.get(0).getToName()).isEqualTo("new-lib");
    }

    @Test
    @DisplayName("제거된 컴포넌트는 REMOVED 타입으로 집계된다")
    void populateModel_detectsRemovedComponents() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Library libA = Library.builder().name("lib-a").version("1.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();
        Library libB = Library.builder().name("lib-b").version("1.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();

        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        ScanComponent compA = ScanComponent.builder().id(100L).library(libA).scanResult(from).build();
        ScanComponent compB = ScanComponent.builder().id(101L).library(libB).scanResult(from).build();
        ScanComponent compA2 = ScanComponent.builder().id(200L).library(libA).scanResult(to).build();
        // lib-b removed in 'to' version

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanComponentRepository.findByScanResultId(1L)).thenReturn(List.of(compA, compB));
        when(scanComponentRepository.findByScanResultId(2L)).thenReturn(List.of(compA2));

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("removedCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<VersionDiffRowDto> rows = (List<VersionDiffRowDto>) model.getAttribute("diffRows");
        assertThat(rows).anyMatch(r -> r.getChangeType() == ChangeType.REMOVED
                && "lib-b".equals(r.getFromName()));
    }

    @Test
    @DisplayName("버전이 변경된 컴포넌트는 UPDATED 타입으로 집계된다")
    void populateModel_detectsUpdatedComponents() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Library libOld = Library.builder().name("spring-core").version("5.3.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();
        Library libNew = Library.builder().name("spring-core").version("6.0.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED).build();

        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        ScanComponent fromComp = ScanComponent.builder().id(100L).library(libOld).scanResult(from).build();
        ScanComponent toComp = ScanComponent.builder().id(200L).library(libNew).scanResult(to).build();

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanComponentRepository.findByScanResultId(1L)).thenReturn(List.of(fromComp));
        when(scanComponentRepository.findByScanResultId(2L)).thenReturn(List.of(toComp));

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("updatedCount")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<VersionDiffRowDto> rows = (List<VersionDiffRowDto>) model.getAttribute("diffRows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo(ChangeType.UPDATED);
        assertThat(rows.get(0).getFromVersion()).isEqualTo("5.3.0");
        assertThat(rows.get(0).getToVersion()).isEqualTo("6.0.0");
    }

    @Test
    @DisplayName("새로 추가되었고 CVE가 있는 컴포넌트는 NEW_THREAT 타입이다")
    void populateModel_detectsNewThreat_forAddedVulnerableComponent() {
        Project project = Project.builder().id(1L).name("Proj").build();

        Cve cve = Cve.builder().cveId("CVE-2021-44228").severity(RiskLevel.CRITICAL)
                .cvssScore(10.0).library(null).build();
        Library vulnLib = Library.builder().name("log4j").version("2.14.0")
                .ecosystem("MAVEN").licenseStatus(LicenseStatus.PERMITTED)
                .cves(List.of(cve)).build();

        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        ScanComponent toComp = ScanComponent.builder().id(200L).library(vulnLib).scanResult(to).build();

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanComponentRepository.findByScanResultId(1L)).thenReturn(List.of());
        when(scanComponentRepository.findByScanResultId(2L)).thenReturn(List.of(toComp));

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("newThreatCount")).isEqualTo(1);
        assertThat(model.getAttribute("addedCount")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        List<VersionDiffRowDto> rows = (List<VersionDiffRowDto>) model.getAttribute("diffRows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo(ChangeType.NEW_THREAT);
    }
}
