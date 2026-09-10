package com.salkcoding.oswl.service.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OswlMetrics 단위 테스트")
class OswlMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OswlMetrics metrics = new OswlMetrics(registry);

    @Test
    @DisplayName("recordScanDuration: outcome 태그별로 타이머가 기록된다")
    void recordScanDuration_recordsTimerByOutcome() {
        metrics.recordScanDuration(1500, "completed");
        metrics.recordScanDuration(500, "completed");
        metrics.recordScanDuration(2000, "failed");

        assertThat(registry.get("oswl.scan.duration").tag("outcome", "completed").timer().count()).isEqualTo(2);
        assertThat(registry.get("oswl.scan.duration").tag("outcome", "completed").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2000.0);
        assertThat(registry.get("oswl.scan.duration").tag("outcome", "failed").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordComponentsIngested: ecosystem은 소문자로 정규화되고 0 이하는 무시된다")
    void recordComponentsIngested_normalizesEcosystem() {
        metrics.recordComponentsIngested("MAVEN", 3);
        metrics.recordComponentsIngested("maven", 2);
        metrics.recordComponentsIngested("npm", 0);
        metrics.recordComponentsIngested(null, 1);

        assertThat(registry.get("oswl.components.ingested.total").tag("ecosystem", "maven").counter().count())
                .isEqualTo(5.0);
        assertThat(registry.get("oswl.components.ingested.total").tag("ecosystem", "unknown").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("oswl.components.ingested.total").tag("ecosystem", "npm").counter()).isNull();
    }

    @Test
    @DisplayName("registerQuickImportGauges: 게이지는 한 번만 등록되고 라이브 값을 읽는다")
    void registerQuickImportGauges_registersOnceAndReadsLiveValues() {
        AtomicInteger pending = new AtomicInteger(2);
        AtomicInteger running = new AtomicInteger(1);

        metrics.registerQuickImportGauges(pending::get, running::get);
        metrics.registerQuickImportGauges(pending::get, running::get);

        assertThat(registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals("oswl.quickimport.queue.depth")).count()).isEqualTo(1);
        assertThat(registry.get("oswl.quickimport.queue.depth").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("oswl.quickimport.running").gauge().value()).isEqualTo(1.0);

        pending.set(5);
        assertThat(registry.get("oswl.quickimport.queue.depth").gauge().value()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("recordAiUsage: 호출·토큰·비용 카운터가 provider/direction 태그로 기록된다")
    void recordAiUsage_recordsCallTokenAndCostCounters() {
        metrics.recordAiUsage("OPENAI", 100, 50, 0.01);
        metrics.recordAiUsage("OPENAI", 200, 0, 0);

        assertThat(registry.get("oswl.ai.calls.total").tag("provider", "openai").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("oswl.ai.tokens.total")
                .tag("provider", "openai").tag("direction", "in").counter().count()).isEqualTo(300.0);
        assertThat(registry.get("oswl.ai.tokens.total")
                .tag("provider", "openai").tag("direction", "out").counter().count()).isEqualTo(50.0);
        assertThat(registry.get("oswl.ai.cost.usd.total").tag("provider", "openai").counter().count())
                .isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("recordGateEvaluation: pass/fail outcome 태그로 카운터가 기록된다")
    void recordGateEvaluation_recordsByOutcome() {
        metrics.recordGateEvaluation(true);
        metrics.recordGateEvaluation(true);
        metrics.recordGateEvaluation(false);

        assertThat(registry.get("oswl.gate.evaluations.total").tag("outcome", "pass").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("oswl.gate.evaluations.total").tag("outcome", "fail").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordExternalApiCall: source/outcome 태그로 카운터가 기록된다")
    void recordExternalApiCall_recordsBySourceAndOutcome() {
        metrics.recordExternalApiCall("depsdev", OswlMetrics.OUTCOME_SUCCESS);
        metrics.recordExternalApiCall("depsdev", OswlMetrics.OUTCOME_RATE_LIMITED);
        metrics.recordExternalApiCall("osv", OswlMetrics.OUTCOME_FAILURE);

        assertThat(registry.get("oswl.external.api.calls.total")
                .tag("source", "depsdev").tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("oswl.external.api.calls.total")
                .tag("source", "depsdev").tag("outcome", "ratelimited").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("oswl.external.api.calls.total")
                .tag("source", "osv").tag("outcome", "failure").counter().count()).isEqualTo(1.0);
    }
}
