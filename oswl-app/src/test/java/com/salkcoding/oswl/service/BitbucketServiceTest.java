package com.salkcoding.oswl.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BitbucketService 단위 테스트")
class BitbucketServiceTest {

    private final BitbucketService service = new BitbucketService();

    // ── getBranches: exception-safe fallback ──────────────────────────────

    @Test
    @DisplayName("getBranches: 요청이 실패하면 ['main'] 폴백을 반환한다 (Cloud)")
    void getBranches_networkFailure_returnsFallback() {
        // No real HTTP server — RestClient/HttpClient will fail
        // BitbucketService.getBranches() catches all exceptions and returns ["main"]
        String invalidUrl = "workspace/repo";
        String token = "bad-token";

        // This should NOT throw but return ["main"] due to the catch-all exception handler
        java.util.List<String> branches = service.getBranches(token, null, null, invalidUrl);

        assertThat(branches).containsExactly("main");
    }

    @Test
    @DisplayName("getBranches: 서버 모드에서도 예외가 발생하면 ['main']을 반환한다")
    void getBranches_serverModeNetworkFailure_returnsFallback() {
        java.util.List<String> branches =
                service.getBranches("token", null, "https://invalid.internal", "PROJECT/repo");

        assertThat(branches).containsExactly("main");
    }

    // ── Service instantiation ─────────────────────────────────────────────

    @Test
    @DisplayName("BitbucketService: 기본 생성자로 인스턴스화할 수 있다")
    void bitbucketService_canBeInstantiated() {
        assertThat(service).isNotNull();
    }

    // ── createVersionBumpPr: invalid repo path throws ─────────────────────

    @Test
    @DisplayName("createVersionBumpPr: 슬래시가 없는 repoPath는 IllegalArgumentException을 던진다")
    void createVersionBumpPr_invalidRepoPath_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                service.createVersionBumpPr(
                        "token", null, "https://bitbucket.example.com",
                        "no-slash-in-path",
                        "main",
                        "org.example:lib", "1.0", "2.0",
                        "PR title", "PR body",
                        java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid Bitbucket repository path");
    }

    @Test
    @DisplayName("getBranches: Server 모드에서 repoPath 형식이 잘못되면 폴백한다")
    void getBranches_serverInvalidPath_returnsFallback() {
        java.util.List<String> branches =
                service.getBranches("token", null, "https://bitbucket.example.com", "INVALID");

        assertThat(branches).containsExactly("main");
    }
}
