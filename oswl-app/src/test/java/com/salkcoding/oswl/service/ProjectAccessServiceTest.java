package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectAccessService unit tests")
class ProjectAccessServiceTest {

    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock ProjectRepository projectRepository;

    @InjectMocks ProjectAccessService projectAccessService;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("system admin can view any project without membership row")
    void systemAdmin_canView() {
        OswlUserPrincipal admin = new OswlUserPrincipal(
                1L, "admin@test.com", "pw", "Admin",
                true, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));

        assertThat(projectAccessService.canViewProject(99L)).isTrue();
        assertThatCode(() -> projectAccessService.assertCanViewProject(99L)).doesNotThrowAnyException();
        verifyNoInteractions(projectMemberRepository);
    }

    @Test
    @DisplayName("member can view project they belong to")
    void member_canView_whenListed() {
        OswlUserPrincipal user = new OswlUserPrincipal(
                2L, "dev@test.com", "pw", "Dev",
                false, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(projectMemberRepository.existsByProjectIdAndUserId(5L, 2L)).thenReturn(true);

        assertThat(projectAccessService.canViewProject(5L)).isTrue();
    }

    @Test
    @DisplayName("non-member receives 403 on assertCanViewProject")
    void nonMember_forbidden() {
        OswlUserPrincipal user = new OswlUserPrincipal(
                3L, "other@test.com", "pw", "Other",
                false, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        when(projectMemberRepository.existsByProjectIdAndUserId(5L, 3L)).thenReturn(false);

        assertThatThrownBy(() -> projectAccessService.assertCanViewProject(5L))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("ensureMember creates row when absent")
    void ensureMember_creates() {
        Project project = Project.builder().id(10L).name("p").build();
        when(projectRepository.findById(10L)).thenReturn(java.util.Optional.of(project));
        when(projectMemberRepository.existsByProjectIdAndUserId(10L, 7L)).thenReturn(false);

        projectAccessService.ensureMember(10L, 7L, null);

        verify(projectMemberRepository).save(any());
    }
}
