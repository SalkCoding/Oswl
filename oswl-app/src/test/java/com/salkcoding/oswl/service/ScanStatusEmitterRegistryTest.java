package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

@Tag(TestTags.FAST)
@DisplayName("ScanStatusEmitterRegistry 단위 테스트")
class ScanStatusEmitterRegistryTest {

    // ── subscribe ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("subscribe: SseEmitter를 반환한다")
    void subscribe_returnsEmitter() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();

        SseEmitter emitter = registry.subscribe(List.of(1L));

        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("subscribe: 여러 projectId로 구독할 수 있다")
    void subscribe_multipleProjects() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();

        SseEmitter emitter = registry.subscribe(List.of(1L, 2L, 3L));

        assertThat(emitter).isNotNull();
    }

    @Test
    @DisplayName("subscribe: 빈 목록으로도 예외 없이 구독된다")
    void subscribe_emptyList_noException() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();

        assertThatNoException().isThrownBy(() -> registry.subscribe(List.of()));
    }

    // ── notifyStatus ──────────────────────────────────────────────────────

    @Test
    @DisplayName("notifyStatus: 구독자가 없는 projectId이면 예외 없이 완료된다")
    void notifyStatus_noSubscribers_noException() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();

        assertThatNoException().isThrownBy(() -> registry.notifyStatus(999L, "COMPLETED"));
    }

    @Test
    @DisplayName("notifyStatus: 구독자가 있으면 이벤트를 발송한다 (예외 없음)")
    void notifyStatus_withSubscriber_noException() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();
        registry.subscribe(List.of(10L));

        // SseEmitter.send()는 실제 HTTP 응답 없이 IOException을 발생시킬 수 있음
        // — 예외 없이 처리됨을 검증 (stale 처리 경로)
        assertThatNoException().isThrownBy(() -> registry.notifyStatus(10L, "RUNNING"));
    }

    @Test
    @DisplayName("notifyStatus: 여러 구독자가 있어도 예외 없이 처리된다")
    void notifyStatus_multipleSubscribers_noException() {
        ScanStatusEmitterRegistry registry = new ScanStatusEmitterRegistry();
        registry.subscribe(List.of(20L));
        registry.subscribe(List.of(20L));
        registry.subscribe(List.of(20L));

        assertThatNoException().isThrownBy(() -> registry.notifyStatus(20L, "COMPLETED"));
    }
}
