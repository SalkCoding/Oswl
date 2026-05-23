package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.github.GitHubAccountDto;
import com.salkcoding.oswl.dto.github.GitHubImportRequest;
import com.salkcoding.oswl.dto.github.GitHubRepoDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.service.GitHubService;
import com.salkcoding.oswl.service.ProjectService;
import com.salkcoding.oswl.service.SessionCipherService;
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
@DisplayName("GitHubApiController 단위 테스트")
class GitHubApiControllerTest {

    @Mock GitHubService       gitHubService;
    @Mock ProjectService      projectService;
    @Mock ProjectRepository   projectRepository;
    @Mock SessionCipherService sessionCipher;
    @Mock HttpSession         session;

    @InjectMocks GitHubApiController controller;

    // ── connect ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect: token 없음 → 400")
    void connect_noToken_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.connect(Map.of(), session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("connect: token blank → 400")
    void connect_blankToken_returns400() {
        ResponseEntity<Map<String, Object>> resp = controller.connect(Map.of("token", "  "), session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("connect: 유효한 token → 세션에 저장 + login 반환")
    void connect_validToken_storesInSessionAndReturnsLogin() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(gitHubService.getUserLogin("mytoken")).thenReturn("octocat");
        when(sessionCipher.encrypt("mytoken")).thenReturn("encrypted");

        ResponseEntity<Map<String, Object>> resp = controller.connect(Map.of("token", "mytoken"), session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("connected")).isEqualTo(true);
        assertThat(resp.getBody().get("login")).isEqualTo("octocat");
        assertThat(tokensMap.get("octocat")).isEqualTo("encrypted");
    }

    @Test
    @DisplayName("connect: GitHubAuthException → 401")
    void connect_authException_returns401() {
        when(gitHubService.getUserLogin(any())).thenThrow(new GitHubService.GitHubAuthException("bad token"));

        ResponseEntity<Map<String, Object>> resp = controller.connect(Map.of("token", "badtoken"), session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── disconnect ────────────────────────────────────────────────────────

    @Test
    @DisplayName("disconnect: 세션 토큰 제거 + 200")
    void disconnect_removesTokensFromSession() {
        ResponseEntity<Map<String, Object>> resp = controller.disconnect(session);

        verify(session).removeAttribute(GitHubApiController.SESSION_GITHUB_TOKENS);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("disconnected")).isEqualTo(true);
    }

    // ── disconnectAccount ─────────────────────────────────────────────────

    @Test
    @DisplayName("disconnectAccount: 해당 계정만 제거")
    void disconnectAccount_removesSpecificAccount() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc1");
        tokensMap.put("other", "enc2");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);

        ResponseEntity<Map<String, Object>> resp = controller.disconnectAccount("octocat", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("removed")).isEqualTo("octocat");
        assertThat(tokensMap).doesNotContainKey("octocat");
        assertThat(tokensMap).containsKey("other");
    }

    @Test
    @DisplayName("disconnectAccount: 마지막 계정 제거 시 세션 attribute 삭제")
    void disconnectAccount_lastAccount_removesSessionAttribute() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc1");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);

        controller.disconnectAccount("octocat", session);

        verify(session).removeAttribute(GitHubApiController.SESSION_GITHUB_TOKENS);
    }

    // ── status ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("status: 토큰 없음 → connected=false")
    void status_noTokens_returnsNotConnected() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<Map<String, Object>> resp = controller.status(session);

        assertThat(resp.getBody().get("connected")).isEqualTo(false);
    }

    @Test
    @DisplayName("status: 유효한 토큰 → connected=true")
    void status_validToken_returnsConnected() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plainToken");
        when(gitHubService.getUserLogin("plainToken")).thenReturn("octocat");

        ResponseEntity<Map<String, Object>> resp = controller.status(session);

        assertThat(resp.getBody().get("connected")).isEqualTo(true);
        assertThat(resp.getBody().get("login")).isEqualTo("octocat");
    }

    @Test
    @DisplayName("status: 토큰 만료 → connected=false, 세션 삭제")
    void status_expiredToken_returnsNotConnected() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "expired");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("expired")).thenReturn("plainToken");
        when(gitHubService.getUserLogin("plainToken"))
                .thenThrow(new GitHubService.GitHubAuthException("expired"));

        ResponseEntity<Map<String, Object>> resp = controller.status(session);

        assertThat(resp.getBody().get("connected")).isEqualTo(false);
        verify(session).removeAttribute(GitHubApiController.SESSION_GITHUB_TOKENS);
    }

    // ── accounts ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("accounts: 토큰 없음 → 401")
    void accounts_noTokens_returns401() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<List<GitHubAccountDto>> resp = controller.accounts(session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("accounts: 유효한 토큰 → 계정 목록 반환")
    void accounts_validToken_returnsAccounts() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        GitHubAccountDto dto = GitHubAccountDto.builder().login("octocat").type("User").build();
        when(gitHubService.getAccounts("plain")).thenReturn(List.of(dto));

        ResponseEntity<List<GitHubAccountDto>> resp = controller.accounts(session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        assertThat(resp.getBody().get(0).getLogin()).isEqualTo("octocat");
    }

    // ── repos ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("repos: 토큰 없음 → 401")
    void repos_noToken_returns401() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<List<GitHubRepoDto>> resp = controller.repos("octocat", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("repos: 유효한 토큰 → repo 목록 반환")
    void repos_validToken_returnsRepos() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        GitHubRepoDto repo = GitHubRepoDto.builder().name("Hello-World").fullName("octocat/Hello-World").build();
        when(gitHubService.getRepos("plain", "octocat")).thenReturn(List.of(repo));

        ResponseEntity<List<GitHubRepoDto>> resp = controller.repos("octocat", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }

    // ── branches ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("branches: 토큰 없음 → 401")
    void branches_noToken_returns401() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<List<String>> resp = controller.branches("octocat", "repo", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("branches: 유효한 토큰 → 브랜치 목록 반환")
    void branches_validToken_returnsBranches() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        when(gitHubService.getBranches("plain", "octocat", "repo")).thenReturn(List.of("main", "develop"));

        ResponseEntity<List<String>> resp = controller.branches("octocat", "repo", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main", "develop");
    }

    @Test
    @DisplayName("branches: 서비스 예외 → main 반환")
    void branches_serviceException_returnsMain() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        when(gitHubService.getBranches(any(), any(), any())).thenThrow(new RuntimeException("network error"));

        ResponseEntity<List<String>> resp = controller.branches("octocat", "repo", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    // ── importRepo ────────────────────────────────────────────────────────

    @Test
    @DisplayName("importRepo: 토큰 없음 → 401")
    void importRepo_noToken_returns401() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);
        GitHubImportRequest req = new GitHubImportRequest("octocat", "Hello-World", "main");

        ResponseEntity<Map<String, Object>> resp = controller.importRepo(req, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("importRepo: 유효한 토큰 → 프로젝트 upsert 후 응답")
    void importRepo_validToken_upsertsAndReturns() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        Project project = Project.builder().id(42L).name("Hello-World").projectUuid("uuid-1234").build();
        when(projectService.upsertFromGitHub("octocat", "Hello-World", "main")).thenReturn(project);
        GitHubImportRequest req = new GitHubImportRequest("octocat", "Hello-World", "main");

        ResponseEntity<Map<String, Object>> resp = controller.importRepo(req, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("projectId")).isEqualTo(42L);
        assertThat(resp.getBody().get("projectName")).isEqualTo("Hello-World");
    }

    // ── branchUpdatedAt ───────────────────────────────────────────────────

    @Test
    @DisplayName("branchUpdatedAt: 토큰 없음 → 401")
    void branchUpdatedAt_noToken_returns401() {
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<Map<String, String>> resp = controller.branchUpdatedAt("octocat", "repo", "main", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("branchUpdatedAt: 유효한 토큰 → updatedAt 반환")
    void branchUpdatedAt_validToken_returnsDate() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("octocat", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        when(gitHubService.getBranchLastCommitDate("plain", "octocat", "repo", "main")).thenReturn("2026-01-01T00:00:00Z");

        ResponseEntity<Map<String, String>> resp = controller.branchUpdatedAt("octocat", "repo", "main", session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("updatedAt")).isEqualTo("2026-01-01T00:00:00Z");
    }

    // ── branchesByProject ─────────────────────────────────────────────────

    @Test
    @DisplayName("branchesByProject: 프로젝트 없음 → main 반환")
    void branchesByProject_projectNotFound_returnsMain() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<List<String>> resp = controller.branchesByProject(99L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branchesByProject: githubRepo is null → main 반환")
    void branchesByProject_nullGithubRepo_returnsMain() {
        Project project = Project.builder().id(1L).name("test").githubRepo(null).build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ResponseEntity<List<String>> resp = controller.branchesByProject(1L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branchesByProject: githubRepo has no slash → main 반환")
    void branchesByProject_githubRepoNoSlash_returnsMain() {
        Project project = Project.builder().id(1L).name("test").githubRepo("noslash").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        ResponseEntity<List<String>> resp = controller.branchesByProject(1L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branchesByProject: 토큰 없음 → main 반환")
    void branchesByProject_noToken_returnsMain() {
        Project project = Project.builder().id(1L).name("repo").githubRepo("owner/repo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(null);

        ResponseEntity<List<String>> resp = controller.branchesByProject(1L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    @Test
    @DisplayName("branchesByProject: 유효한 토큰 → 브랜치 목록 반환")
    void branchesByProject_validToken_returnsBranches() {
        Project project = Project.builder().id(1L).name("repo").githubRepo("owner/repo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("owner", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        when(gitHubService.getBranches("plain", "owner", "repo")).thenReturn(List.of("main", "dev"));

        ResponseEntity<List<String>> resp = controller.branchesByProject(1L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main", "dev");
    }

    @Test
    @DisplayName("branchesByProject: 서비스 예외 → main 반환")
    void branchesByProject_exception_returnsMain() {
        Project project = Project.builder().id(1L).name("repo").githubRepo("owner/repo").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("owner", "enc");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc")).thenReturn("plain");
        when(gitHubService.getBranches(any(), any(), any())).thenThrow(new RuntimeException("network error"));

        ResponseEntity<List<String>> resp = controller.branchesByProject(1L, session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsExactly("main");
    }

    // ── accounts: exception in inner loop ────────────────────────────────

    @Test
    @DisplayName("accounts: 특정 토큰에서 예외 발생해도 다른 토큰 결과 포함")
    void accounts_withExceptionForOneToken_stillReturnsResults() {
        Map<String, String> tokensMap = new LinkedHashMap<>();
        tokensMap.put("user1", "enc1");
        tokensMap.put("user2", "enc2");
        when(session.getAttribute(GitHubApiController.SESSION_GITHUB_TOKENS)).thenReturn(tokensMap);
        when(sessionCipher.decrypt("enc1")).thenThrow(new RuntimeException("decrypt failed"));
        when(sessionCipher.decrypt("enc2")).thenReturn("plain2");
        GitHubAccountDto acc = mock(GitHubAccountDto.class);
        when(acc.getLogin()).thenReturn("user2");
        when(gitHubService.getAccounts("plain2")).thenReturn(List.of(acc));

        ResponseEntity<List<GitHubAccountDto>> resp = controller.accounts(session);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
    }
}

