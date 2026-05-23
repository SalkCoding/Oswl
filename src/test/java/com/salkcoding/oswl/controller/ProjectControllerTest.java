package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.Project;
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
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

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

    @Test
    @DisplayName("permanentDeleteProject()는 서비스를 호출하고 204를 반환한다")
    void permanentDeleteProject_returns204() {
        doNothing().when(projectService).permanentDelete(3L);

        ResponseEntity<Void> response = controller.permanentDeleteProject(3L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).permanentDelete(3L);
    }

    @Test
    @DisplayName("permanentDeleteAll()은 서비스를 호출하고 204를 반환한다")
    void permanentDeleteAll_returns204() {
        doNothing().when(projectService).permanentDeleteAll();

        ResponseEntity<Void> response = controller.permanentDeleteAll();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).permanentDeleteAll();
    }

    @Test
    @DisplayName("permanentDeleteSelected()는 선택된 ID 목록을 서비스에 전달하고 204를 반환한다")
    void permanentDeleteSelected_returns204() {
        List<Long> ids = List.of(1L, 2L, 3L);
        doNothing().when(projectService).permanentDeleteSelected(ids);

        ResponseEntity<Void> response = controller.permanentDeleteSelected(ids);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).permanentDeleteSelected(ids);
    }

    @Test
    @DisplayName("restoreSelected()는 선택된 ID 목록을 서비스에 전달하고 204를 반환한다")
    void restoreSelected_returns204() {
        List<Long> ids = List.of(10L, 20L);
        doNothing().when(projectService).restoreSelected(ids);

        ResponseEntity<Void> response = controller.restoreSelected(ids);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectService).restoreSelected(ids);
    }

    @Test
    @DisplayName("createProject()는 프로젝트 생성 후 201 Created를 반환한다")
    void createProject_returns201_withCreatedProject() {
        Project saved = Project.builder().id(99L).name("NewProject").build();
        when(projectService.create("NewProject")).thenReturn(saved);

        ProjectController.CreateProjectRequest req = new ProjectController.CreateProjectRequest("NewProject");
        ResponseEntity<Map<String, Object>> response = controller.createProject(req);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("id", 99L);
        assertThat(response.getBody()).containsEntry("name", "NewProject");
        verify(projectService).create("NewProject");
    }

    // ── index / cards / cli / git ─────────────────────────────────────────

    @Test
    @DisplayName("index: returns projects/index view")
    void index_returnsView() {
        Model model = mock(Model.class);
        when(projectService.findAll()).thenReturn(List.of());

        String view = controller.index(model);

        assertThat(view).isEqualTo("projects/index");
    }

    @Test
    @DisplayName("projectCardsFragment: returns fragment view")
    void projectCardsFragment_returnsView() {
        Model model = mock(Model.class);
        when(projectService.findAll()).thenReturn(List.of());

        String view = controller.projectCardsFragment(model);

        assertThat(view).isEqualTo("projects/index :: projectCardsGrid");
    }

    @Test
    @DisplayName("cliIntegration: redirects to projects")
    void cliIntegration_redirects() {
        String view = controller.cliIntegration();
        assertThat(view).isEqualTo("redirect:/projects");
    }

    @Test
    @DisplayName("gitIntegration: redirects to quick-import")
    void gitIntegration_redirects() {
        String view = controller.gitIntegration();
        assertThat(view).isEqualTo("redirect:/projects/quick-import");
    }

    @Test
    @DisplayName("scanStatusStream: returns SSE emitter")
    void scanStatusStream_returnsEmitter() {
        SseEmitter emitter = new SseEmitter();
        when(scanStatusEmitterRegistry.subscribe(List.of(1L, 2L))).thenReturn(emitter);

        SseEmitter result = controller.scanStatusStream(List.of(1L, 2L));

        assertThat(result).isEqualTo(emitter);
    }
}
