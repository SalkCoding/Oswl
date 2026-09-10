package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.dto.AdminProjectRefDto;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminProjectController 단위 테스트")
class AdminProjectControllerTest {

    @Mock ProjectRepository projectRepository;
    @InjectMocks AdminProjectController controller;

    @Test
    @DisplayName("listActiveProjects()는 활성 프로젝트의 id/name만 반환한다")
    void listActiveProjects_returnsIdAndNameOnly() {
        Project p1 = mock(Project.class);
        when(p1.getId()).thenReturn(1L);
        when(p1.getName()).thenReturn("alpha");
        Project p2 = mock(Project.class);
        when(p2.getId()).thenReturn(2L);
        when(p2.getName()).thenReturn("beta");
        when(projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc())
                .thenReturn(List.of(p1, p2));

        List<AdminProjectRefDto> result = controller.listActiveProjects();

        assertThat(result).containsExactly(
                new AdminProjectRefDto(1L, "alpha"),
                new AdminProjectRefDto(2L, "beta"));
    }

    @Test
    @DisplayName("listActiveProjects()는 활성 프로젝트가 없으면 빈 목록을 반환한다")
    void listActiveProjects_noProjects_returnsEmpty() {
        when(projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc())
                .thenReturn(List.of());

        assertThat(controller.listActiveProjects()).isEmpty();
    }
}
