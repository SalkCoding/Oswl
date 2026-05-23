package com.salkcoding.oswl.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GitLabService 단위 테스트")
class GitLabServiceTest {

    private final GitLabService service = new GitLabService();

    // ── getBranches: exception-safe fallback ──────────────────────────────

    @Test
    @DisplayName("getBranches: 네트워크 오류가 발생하면 ['main'] 폴백을 반환한다 (Cloud)")
    void getBranches_networkFailure_returnsFallback() {
        // getBranches() catches all exceptions and returns List.of("main")
        List<String> branches = service.getBranches("bad-token", null, "namespace/project");

        assertThat(branches).containsExactly("main");
    }

    @Test
    @DisplayName("getBranches: 잘못된 serverUrl에서도 예외 없이 ['main']을 반환한다")
    void getBranches_invalidServerUrl_returnsFallback() {
        List<String> branches = service.getBranches(
                "token", "https://invalid.internal", "namespace/project");

        assertThat(branches).containsExactly("main");
    }

    // ── Service instantiation ─────────────────────────────────────────────

    @Test
    @DisplayName("GitLabService: 기본 생성자로 인스턴스화할 수 있다")
    void gitLabService_canBeInstantiated() {
        assertThat(service).isNotNull();
    }
}
