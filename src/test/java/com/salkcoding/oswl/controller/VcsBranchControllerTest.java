package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.BitbucketService;
import com.salkcoding.oswl.service.GitHubService;
import com.salkcoding.oswl.service.GitLabService;
import com.salkcoding.oswl.service.VcsAuthTokenService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VcsBranchController 단위 테스트")
class VcsBranchControllerTest {

    @Mock ProjectRepository           projectRepository;
    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @Mock EncryptionService           encryptionService;
    @Mock VcsAuthTokenService          vcsAuthTokenService;
    @Mock GitHubService               gitHubService;
    @Mock GitLabService               gitLabService;
    @Mock BitbucketService            bitbucketService;
    @Mock HttpSession                 session;

    @InjectMocks VcsBranchController controller;

    private OswlUserPrincipal principal(Long userId) {
        return new OswlUserPrincipal(
                userId, "user@test.com", "hash", "Test User",
                false, true, List.of(), Set.of(), Set.of(), false);
    }

    // ── project not found / bad repo path ─────────────────────────────────

    @Test
    @DisplayName("branches: 프로젝트 없음 → [main] 반환")
    void branches_projectNotFound_returnsMain() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: githubRepo null → [main] 반환")
    void branches_nullGithubRepo_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo(null).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: githubRepo에 /가 없음 → [main] 반환")
    void branches_repoNoSlash_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("noslash").vcsProvider(VcsProvider.GITHUB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    // ── GITHUB provider ───────────────────────────────────────────────────

    @Test
    @DisplayName("branches: GitHub 토큰 없음 → [main] 반환")
    void branches_github_noToken_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("octocat/repo").vcsProvider(VcsProvider.GITHUB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(vcsAuthTokenService.resolveGithubToken(session, 1L, "octocat")).thenReturn(null);

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: GitHub 정상 → 브랜치 목록 반환")
    void branches_github_validToken_returnsBranches() {
        Project p = Project.builder().id(1L).githubRepo("octocat/repo").vcsProvider(VcsProvider.GITHUB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(vcsAuthTokenService.resolveGithubToken(session, 1L, "octocat")).thenReturn("plainToken");
        when(vcsAuthTokenService.getConnection(1L, VcsProvider.GITHUB)).thenReturn(null);
        when(gitHubService.getBranches("plainToken", "octocat", "repo", null)).thenReturn(List.of("main", "dev"));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main", "dev");
    }

    @Test
    @DisplayName("branches: GitHub 서비스 예외 → [main] 반환")
    void branches_github_exception_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("octocat/repo").vcsProvider(VcsProvider.GITHUB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(vcsAuthTokenService.resolveGithubToken(session, 1L, "octocat")).thenReturn("plain");
        when(vcsAuthTokenService.getConnection(1L, VcsProvider.GITHUB)).thenReturn(null);
        when(gitHubService.getBranches(any(), any(), any(), any())).thenThrow(new RuntimeException("error"));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    // ── GITLAB provider ───────────────────────────────────────────────────

    @Test
    @DisplayName("branches: GitLab 주인공 null → [main] 반환")
    void branches_gitlab_nullPrincipal_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("group/repo").vcsProvider(VcsProvider.GITLAB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));

        ResponseEntity<List<String>> resp = controller.branches(1L, null, session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: GitLab 연결 없음 → [main] 반환")
    void branches_gitlab_noConnection_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("group/repo").vcsProvider(VcsProvider.GITLAB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITLAB))
                .thenReturn(Optional.empty());

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: GitLab 정상 → 브랜치 목록 반환")
    void branches_gitlab_valid_returnsBranches() {
        Project p = Project.builder().id(1L).githubRepo("group/repo").vcsProvider(VcsProvider.GITLAB).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        UserVcsConnection conn = UserVcsConnection.builder()
                .serverUrl("https://gitlab.com")
                .accessTokenEncrypted("enc")
                .build();
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITLAB))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("enc")).thenReturn("glpat-token");
        when(gitLabService.getBranches("glpat-token", "https://gitlab.com", "group/repo"))
                .thenReturn(List.of("main", "feature/x"));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main", "feature/x");
    }

    // ── BITBUCKET provider ────────────────────────────────────────────────

    @Test
    @DisplayName("branches: Bitbucket 연결 없음 → [main] 반환")
    void branches_bitbucket_noConnection_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("workspace/repo").vcsProvider(VcsProvider.BITBUCKET).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.BITBUCKET))
                .thenReturn(Optional.empty());

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branches: Bitbucket 정상 → 브랜치 목록 반환")
    void branches_bitbucket_valid_returnsBranches() {
        Project p = Project.builder().id(1L).githubRepo("workspace/repo").vcsProvider(VcsProvider.BITBUCKET).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));
        UserVcsConnection conn = UserVcsConnection.builder()
                .serverUrl("https://bitbucket.org")
                .vcsUsername("myuser")
                .accessTokenEncrypted("enc")
                .build();
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.BITBUCKET))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("enc")).thenReturn("app-password");
        when(bitbucketService.getBranches("app-password", "myuser", "https://bitbucket.org", "workspace/repo"))
                .thenReturn(List.of("main", "release/1.0"));

        ResponseEntity<List<String>> resp = controller.branches(1L, principal(1L), session);

        assertThat(resp.getBody()).containsExactly("main", "release/1.0");
    }

    @Test
    @DisplayName("branches: Bitbucket null principal → [main] 반환")
    void branches_bitbucket_nullPrincipal_returnsMain() {
        Project p = Project.builder().id(1L).githubRepo("workspace/repo").vcsProvider(VcsProvider.BITBUCKET).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(p));

        ResponseEntity<List<String>> resp = controller.branches(1L, null, session);

        assertThat(resp.getBody()).containsExactly("main");
    }
}
