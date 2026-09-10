package com.salkcoding.oswl.service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Central home for OsWL business metrics exposed via {@code /actuator/prometheus}.
 *
 * Keeping every metric name and tag in one typed facade so instrumentation sites stay
 * one-liners and tag cardinality stays bounded — no project ids, repo URLs, or component
 * names ever appear as tag values. All metric names carry the {@code oswl.} prefix.
 */
@Component
public class OswlMetrics {

    /** {@link #recordExternalApiCall} outcome when the call completed normally. */
    public static final String OUTCOME_SUCCESS = "success";
    /** {@link #recordExternalApiCall} outcome when the call failed for a non-rate-limit reason. */
    public static final String OUTCOME_FAILURE = "failure";
    /** {@link #recordExternalApiCall} outcome when the upstream answered with a rate-limit status. */
    public static final String OUTCOME_RATE_LIMITED = "ratelimited";

    private final MeterRegistry registry;

    /** Guards against double gauge registration (gauges hold strong references to their suppliers). */
    private volatile boolean quickImportGaugesRegistered;

    public OswlMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * End-to-end scan pipeline duration, tagged by {@code outcome=completed|failed}.
     * The percentile histogram is published so Grafana can compute p95/p99 server-side.
     */
    public void recordScanDuration(long durationMs, String outcome) {
        Timer.builder("oswl.scan.duration")
                .description("End-to-end scan pipeline duration")
                .tag("outcome", outcome)
                .publishPercentileHistogram(true)
                .register(registry)
                .record(Math.max(0, durationMs), TimeUnit.MILLISECONDS);
    }

    /** Components persisted by one scan ingest, tagged by lowercase {@code ecosystem}. */
    public void recordComponentsIngested(String ecosystem, int count) {
        if (count <= 0) {
            return;
        }
        registry.counter("oswl.components.ingested.total",
                "ecosystem", normalize(ecosystem)).increment(count);
    }

    /**
     * Registers the Quick Import queue gauges exactly once, reading straight from the live
     * queue/counter objects — no per-job meter registration, so nothing leaks with job churn.
     */
    public void registerQuickImportGauges(Supplier<Number> pendingQueueDepth, Supplier<Number> runningImports) {
        if (quickImportGaugesRegistered) {
            return;
        }
        synchronized (this) {
            if (quickImportGaugesRegistered) {
                return;
            }
            Gauge.builder("oswl.quickimport.queue.depth", pendingQueueDepth, s -> s.get().doubleValue())
                    .description("Quick Import jobs waiting for a worker slot")
                    .register(registry);
            Gauge.builder("oswl.quickimport.running", runningImports, s -> s.get().doubleValue())
                    .description("Quick Import jobs currently running")
                    .register(registry);
            quickImportGaugesRegistered = true;
        }
    }

    /** One recorded AI call: call count, prompt/completion tokens, and estimated USD cost. */
    public void recordAiUsage(String provider, int promptTokens, int completionTokens, double costUsd) {
        String p = normalize(provider);
        registry.counter("oswl.ai.calls.total", "provider", p).increment();
        if (promptTokens > 0) {
            registry.counter("oswl.ai.tokens.total", "provider", p, "direction", "in").increment(promptTokens);
        }
        if (completionTokens > 0) {
            registry.counter("oswl.ai.tokens.total", "provider", p, "direction", "out").increment(completionTokens);
        }
        if (costUsd > 0) {
            registry.counter("oswl.ai.cost.usd.total", "provider", p).increment(costUsd);
        }
    }

    /** One security-gate evaluation, tagged by {@code outcome=pass|fail}. */
    public void recordGateEvaluation(boolean passed) {
        registry.counter("oswl.gate.evaluations.total",
                "outcome", passed ? "pass" : "fail").increment();
    }

    /**
     * One outbound call to an external data source, tagged by {@code source}
     * (depsdev, osv, epss, kev, github-advisory, nvd) and {@code outcome}
     * ({@value #OUTCOME_SUCCESS}|{@value #OUTCOME_FAILURE}|{@value #OUTCOME_RATE_LIMITED}).
     */
    public void recordExternalApiCall(String source, String outcome) {
        registry.counter("oswl.external.api.calls.total",
                "source", source, "outcome", outcome).increment();
    }

    private static String normalize(String tagValue) {
        return tagValue == null || tagValue.isBlank()
                ? "unknown"
                : tagValue.strip().toLowerCase(Locale.ROOT);
    }
}
