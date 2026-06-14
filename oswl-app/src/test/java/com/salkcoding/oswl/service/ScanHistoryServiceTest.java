package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.ScanHistoryRowDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ProjectVersionRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanHistoryService 단위 테스트")
class ScanHistoryServiceTest {

    @Mock ProjectRepository       projectRepository;
    @Mock ScanResultRepository    scanResultRepository;
    @Mock ScanComponentRepository scanComponentRepository;
    @Mock ProjectVersionRepository projectVersionRepository;

    @InjectMocks
    ScanHistoryService scanHistoryService;

    @Test
    @DisplayName("프로젝트가 없으면 IllegalArgumentException을 던진다")
    void populateModel_throwsException_whenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scanHistoryService.populateModel(99L, new ConcurrentModel()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("스캔이 없으면 scanRows가 빈 리스트다")
    void populateModel_emptyScanRows_whenNoScans() {
        Project project = Project.builder().id(1L).name("proj").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findAllByProjectIdOrderByScannedAtDesc(1L)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        scanHistoryService.populateModel(1L, model);

        assertThat((List<?>) model.getAttribute("scanRows")).isEmpty();
        assertThat(model.getAttribute("totalScans")).isEqualTo(0);
    }

    @Test
    @DisplayName("스캔이 있으면 scanRows에 올바른 데이터가 담긴다")
    void populateModel_fillsScanRows_whenScansExist() {
        Project project = Project.builder().id(1L).name("proj").build();

        ScanResult scan = ScanResult.builder()
                .id(10L)
                .project(project)
                .version("1.2.3")
                .status(ScanStatus.COMPLETED)
                .build();
        scan.setScannedAt(LocalDateTime.of(2026, 1, 15, 12, 0));

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findAllByProjectIdOrderByScannedAtDesc(1L)).thenReturn(List.of(scan));
        when(scanComponentRepository.countByScanResultId(10L)).thenReturn(5L);
        when(projectVersionRepository.findByProjectAndBranch(any(), any()))
                .thenReturn(Optional.empty());

        Model model = new ConcurrentModel();
        scanHistoryService.populateModel(1L, model);

        @SuppressWarnings("unchecked")
        List<ScanHistoryRowDto> rows = (List<ScanHistoryRowDto>) model.getAttribute("scanRows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScanId()).isEqualTo(10L);
        assertThat(rows.get(0).getVersion()).isEqualTo("1.2.3");
        assertThat(rows.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(rows.get(0).getComponentCount()).isEqualTo(5L);
        assertThat(model.getAttribute("totalScans")).isEqualTo(1);
    }

    @Test
    @DisplayName("version이 null이면 '-'로 표시된다")
    void populateModel_showsDash_whenVersionIsNull() {
        Project project = Project.builder().id(1L).name("proj").build();

        ScanResult scan = ScanResult.builder()
                .id(11L)
                .project(project)
                .status(ScanStatus.FAILED)
                .build();

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findAllByProjectIdOrderByScannedAtDesc(1L)).thenReturn(List.of(scan));
        when(scanComponentRepository.countByScanResultId(11L)).thenReturn(0L);

        Model model = new ConcurrentModel();
        scanHistoryService.populateModel(1L, model);

        @SuppressWarnings("unchecked")
        List<ScanHistoryRowDto> rows = (List<ScanHistoryRowDto>) model.getAttribute("scanRows");
        assertThat(rows.get(0).getVersion()).isEqualTo("-");
        assertThat(rows.get(0).getScannedAt()).isEqualTo("-");
    }
}
