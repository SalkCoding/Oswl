package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.dto.AddProjectMemberRequest;
import com.salkcoding.oswl.dto.ProjectMemberDto;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectMemberService;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@Tag(TestTags.WEB)
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectMemberController")
class ProjectMemberControllerTest {

    @Mock ProjectMemberService projectMemberService;
    @Mock ProjectAccessService projectAccessService;
    @InjectMocks ProjectMemberController controller;

    @Test
    @DisplayName("list returns members")
    void list_returnsMembers() {
        ProjectMemberDto dto = ProjectMemberDto.builder()
                .userId(2L).email("dev@test.com").role(ProjectMemberRole.MEMBER).build();
        when(projectMemberService.listMembers(1L)).thenReturn(List.of(dto));

        ResponseEntity<List<ProjectMemberDto>> resp = controller.list(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("add delegates to service")
    void add_delegates() {
        AddProjectMemberRequest req = new AddProjectMemberRequest();
        req.setEmail("new@test.com");
        req.setRole(ProjectMemberRole.MEMBER);
        ProjectMemberDto dto = ProjectMemberDto.builder().userId(3L).email("new@test.com").build();
        when(projectMemberService.addMember(1L, req)).thenReturn(dto);

        ResponseEntity<ProjectMemberDto> resp = controller.add(1L, req);

        assertThat(resp.getBody().getEmail()).isEqualTo("new@test.com");
    }

    @Test
    @DisplayName("remove returns removed true")
    void remove_ok() {
        ResponseEntity<Map<String, Boolean>> resp = controller.remove(1L, 5L);

        verify(projectMemberService).removeMember(1L, 5L);
        assertThat(resp.getBody()).containsEntry("removed", true);
    }
}
