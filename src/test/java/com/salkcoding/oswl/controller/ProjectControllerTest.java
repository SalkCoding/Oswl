package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.service.ProjectService;
import com.salkcoding.oswl.service.ScanStatusEmitterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectController 단위 테스트")
class ProjectControllerTest {

    @Mock ProjectService projectService;
    @Mock ScanStatusEmitterRegistry scanStatusEmitterRegistry;
    @InjectMocks ProjectController controller;

    @Test
    @DisplayName("listJson()은 서비스 결과를 200 OK로 반환한다")
    void listJson_returnsProjectList() {
        ProjectSummaryDto dto = ProjectSummaryDto.builder()
                .id(1L).name("TestProject").version("1.0").lastScanned("-")
                .securityCritical(0).securityHigh(0).securityMedium(0).securityLow(0)
                .licenseCritical(0).licenseHigh(0).licenseMedium(0).licenseLow(0)
                .build();
        when(projectService.findAll()).thenReturn(List.of(dto));

        ResponseEntity<List<ProjectSummaryDto>> response = controller.listJson();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getName()).isEqualTo("TestProject");
    }

    @Test
    @DisplayName("listJson()은 프로젝트가 없으면 빈 배열을 반환한다")
    void listJson_returnsEmptyArray_whenNoProjects() {
        when(projectService.findAll()).thenReturn(List.of());

        ResponseEntity<List<ProjectSummaryDto>> response = controller.listJson();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("deleteProject()는 서비스를 호출하고 204를 반환한다")
    void deleteProject_returns204() {
        doNothing().when(projectService).delete(1L);

        ResponseEntity<Void> response = controller.deleteProject(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).delete(1L);
    }

    @Test
    @DisplayName("restoreProject()는 서비스를 호출하고 204를 반환한다")
    void restoreProject_returns204() {
        doNothing().when(projectService).restore(1L);

        ResponseEntity<Void> response = controller.restoreProject(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).restore(1L);
    }
}