package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionDiffRowDto.ChangeType;
import com.salkcoding.oswl.repository.ProjectRepository;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("VersionDiffService 단위 테스트")
class VersionDiffServiceTest {

    @Mock ProjectRepository        projectRepository;
    @Mock ScanResultRepository     scanResultRepository;
    @Mock ScanVersionDiffAnalyzer scanVersionDiffAnalyzer;
    @Mock AiAnalysisService        aiAnalysisService;

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
        versionDiffService.populateModel(1L, 10L, 10L, model);

        assertThat((List<?>) model.getAttribute("diffRows")).isEmpty();
        assertThat(model.getAttribute("totalCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("새로 추가된 컴포넌트는 ADDED 타입으로 집계된다")
    void populateModel_detectsAddedComponents() {
        Project project = Project.builder().id(1L).name("Proj").build();
        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));

        VersionDiffRowDto row = VersionDiffRowDto.builder()
                .toName("new-lib").toVersion("2.0").changeType(ChangeType.ADDED).build();
        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanVersionDiffAnalyzer.compare(1L, 2L))
                .thenReturn(new ScanVersionDiffAnalyzer.DiffResult(1, 0, 0, 0, List.of(row), "- ADDED: new-lib 2.0"));
        when(aiAnalysisService.summarizeVersionDiff(anyString(), anyString(), anyString(),
                anyInt(), anyInt(), anyInt(), anyInt(), anyString()))
                .thenReturn("AI diff insight");

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("addedCount")).isEqualTo(1);
        assertThat(model.getAttribute("removedCount")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        List<VersionDiffRowDto> rows = (List<VersionDiffRowDto>) model.getAttribute("diffRows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getChangeType()).isEqualTo(ChangeType.ADDED);
    }

    @Test
    @DisplayName("캐시된 version diff AI insight가 있으면 live AI 호출을 생략한다")
    void populateModel_usesCachedDiffInsight_whenAvailable() {
        Project project = Project.builder().id(1L).name("Proj").build();
        ScanResult from = ScanResult.builder().id(1L).project(project).version("1.0")
                .status(ScanStatus.COMPLETED).build();
        from.setScannedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        ScanResult to = ScanResult.builder().id(2L).project(project).version("2.0")
                .status(ScanStatus.COMPLETED).build();
        to.setScannedAt(LocalDateTime.of(2026, 2, 1, 0, 0));
        to.updateVersionDiffInsight("cached insight", 1L);

        when(projectRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(project));
        when(scanResultRepository.findCompletedByProjectId(1L)).thenReturn(List.of(to, from));
        when(scanVersionDiffAnalyzer.compare(1L, 2L))
                .thenReturn(new ScanVersionDiffAnalyzer.DiffResult(0, 0, 0, 0, List.of(), "- No notable"));

        Model model = new ConcurrentModel();
        versionDiffService.populateModel(1L, 1L, 2L, model);

        assertThat(model.getAttribute("diffAiInsight")).isEqualTo("cached insight");
        verifyNoInteractions(aiAnalysisService);
    }
}
