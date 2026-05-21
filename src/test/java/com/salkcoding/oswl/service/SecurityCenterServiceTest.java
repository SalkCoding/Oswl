package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
        // addEmptySummary()가 설정하는 실제 속성 이름으로 검증
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
}
