package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag(TestTags.FAST)
@DisplayName("GitHubService 단위 테스트")
class GitHubServiceTest {

    // ── GitHubAuthException ───────────────────────────────────────────────

    @Test
    @DisplayName("GitHubAuthException: 메시지를 올바르게 저장한다")
    void gitHubAuthException_storesMessage() {
        GitHubService.GitHubAuthException ex =
                new GitHubService.GitHubAuthException("token expired");

        assertThat(ex.getMessage()).isEqualTo("token expired");
    }

    @Test
    @DisplayName("GitHubAuthException: RuntimeException을 상속한다")
    void gitHubAuthException_isRuntimeException() {
        GitHubService.GitHubAuthException ex =
                new GitHubService.GitHubAuthException("invalid");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("GitHubAuthException: throw/catch가 가능하다")
    void gitHubAuthException_canBeThrown() {
        assertThatThrownBy(() -> {
            throw new GitHubService.GitHubAuthException("auth failed");
        }).isInstanceOf(GitHubService.GitHubAuthException.class)
          .hasMessage("auth failed");
    }

    // ── Service instantiation ─────────────────────────────────────────────

    @Test
    @DisplayName("GitHubService: 기본 생성자로 인스턴스화할 수 있다")
    void gitHubService_canBeInstantiated() {
        GitHubService service = new GitHubService();

        assertThat(service).isNotNull();
    }
}
