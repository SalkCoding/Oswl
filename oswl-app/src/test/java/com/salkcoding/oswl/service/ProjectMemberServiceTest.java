package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.ProjectMember;
import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import com.salkcoding.oswl.dto.AddProjectMemberRequest;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectMemberRepository;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectMemberService")
class ProjectMemberServiceTest {

    @Mock ProjectMemberRepository projectMemberRepository;
    @Mock ProjectAccessService projectAccessService;
    @Mock UserRepository userRepository;
    @InjectMocks ProjectMemberService service;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("addMember rejects unknown email")
    void addMember_unknownEmail() {
        loginAsProjectAdmin(10L);
        AddProjectMemberRequest req = new AddProjectMemberRequest();
        req.setEmail("nobody@test.com");
        when(userRepository.findByEmail("nobody@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addMember(5L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No user with email");
    }

    @Test
    @DisplayName("addMember rejects duplicate membership")
    void addMember_duplicateMember() {
        loginAsProjectAdmin(10L);
        User user = User.builder().id(20L).email("dev@test.com").build();
        AddProjectMemberRequest req = new AddProjectMemberRequest();
        req.setEmail("dev@test.com");
        when(userRepository.findByEmail("dev@test.com")).thenReturn(Optional.of(user));
        when(projectMemberRepository.existsByProjectIdAndUserId(5L, 20L)).thenReturn(true);

        assertThatThrownBy(() -> service.addMember(5L, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already a member");
    }

    @Test
    @DisplayName("removeMember blocks removing the last admin")
    void removeMember_lastAdmin_forbidden() {
        loginAsProjectAdmin(10L);
        ProjectMember soleAdmin = ProjectMember.builder()
                .userId(10L).role(ProjectMemberRole.ADMIN).build();
        when(projectMemberRepository.existsByProjectIdAndUserId(5L, 10L)).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndRole(5L, ProjectMemberRole.ADMIN))
                .thenReturn(List.of(soleAdmin));

        assertThatThrownBy(() -> service.removeMember(5L, 10L))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("last project admin");
    }

    @Test
    @DisplayName("non-admin cannot manage members")
    void addMember_nonAdmin_forbidden() {
        OswlUserPrincipal member = new OswlUserPrincipal(
                11L, "member@test.com", "pw", "Member",
                false, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));
        when(projectAccessService.currentPrincipal()).thenReturn(member);

        AddProjectMemberRequest req = new AddProjectMemberRequest();
        req.setEmail("other@test.com");

        assertThatThrownBy(() -> service.addMember(5L, req))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("addMember delegates ensureMember for new user")
    void addMember_success() {
        loginAsProjectAdmin(10L);
        User user = User.builder().id(20L).email("dev@test.com").displayName("Dev").build();
        AddProjectMemberRequest req = new AddProjectMemberRequest();
        req.setEmail("Dev@Test.com");
        req.setRole(ProjectMemberRole.MEMBER);
        when(userRepository.findByEmail("dev@test.com")).thenReturn(Optional.of(user));
        when(projectMemberRepository.existsByProjectIdAndUserId(5L, 20L)).thenReturn(false);
        ProjectMember saved = ProjectMember.builder()
                .userId(20L).role(ProjectMemberRole.MEMBER).build();
        when(projectMemberRepository.findByProjectIdAndUserId(5L, 20L)).thenReturn(List.of(saved));
        when(userRepository.findById(20L)).thenReturn(Optional.of(user));

        service.addMember(5L, req);

        verify(projectAccessService).ensureMember(5L, 20L, ProjectMemberRole.MEMBER);
    }

    private void loginAsProjectAdmin(long userId) {
        OswlUserPrincipal admin = new OswlUserPrincipal(
                userId, "admin@test.com", "pw", "Admin",
                true, true, List.of(), Set.of(), Set.of(), false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        when(projectAccessService.currentPrincipal()).thenReturn(admin);
    }
}
