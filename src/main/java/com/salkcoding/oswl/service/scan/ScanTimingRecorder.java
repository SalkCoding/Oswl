package com.salkcoding.oswl.service.scan;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accumulates per-phase elapsed time for a single scan (keyed by scanResultId, or by
 * Quick Import jobId before a scanResultId exists) so the pipeline can emit one
 * {@code [Timing]} summary line instead of scattering durations across debug logs.
 *
 * Multiple {@link #record} calls for the same phase within the same scan accumulate
 * (e.g. deps.dev is called once for cache-hit refresh and again for cache-miss fetch —
 * both contribute to the same "depsdev" total).
 */
@Component
public class ScanTimingRecorder {

    /** Canonical display order; phases not in this list (shouldn't normally happen) print after it. */
    private static final List<String> PHASE_ORDER = List.of(
            "clone", "parse", "ingest", "depsdev", "osv", "threatintel",
            "ai.cve", "ai.license", "ai.posture", "ai.trend", "ai.diff", "cleanup");

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> timings = new ConcurrentHashMap<>();

    /** {@link AutoCloseable} whose {@code close()} does not declare a checked exception, so callers can use it in try-with-resources without a catch clause. */
    public interface TimingScope extends AutoCloseable {
        @Override
        void close();
    }

    /** Try-with-resources helper: records elapsed time under {@code phaseName} on close. */
    public TimingScope phase(String scanKey, String phaseName) {
        long startNanos = System.nanoTime();
        return () -> record(scanKey, phaseName, (System.nanoTime() - startNanos) / 1_000_000);
    }

    public void record(String scanKey, String phaseName, long elapsedMs) {
        if (scanKey == null) {
            return;
        }
        timings.computeIfAbsent(scanKey, k -> new ConcurrentHashMap<>())
                .merge(phaseName, elapsedMs, Long::sum);
    }

    /** Total elapsed time recorded across all phases for this scan, in milliseconds. */
    public long totalMs(String scanKey) {
        Map<String, Long> phases = timings.get(scanKey);
        if (phases == null) {
            return 0;
        }
        return phases.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Renders {@code "clone=3.2s parse=8.1s ..."} in canonical phase order; empty string if nothing recorded. */
    public String summarizePhases(String scanKey) {
        Map<String, Long> phases = timings.get(scanKey);
        if (phases == null || phases.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String phase : PHASE_ORDER) {
            Long ms = phases.get(phase);
            if (ms == null) {
                continue;
            }
            appendPhase(sb, phase, ms);
        }
        for (Map.Entry<String, Long> entry : phases.entrySet()) {
            if (!PHASE_ORDER.contains(entry.getKey())) {
                appendPhase(sb, entry.getKey(), entry.getValue());
            }
        }
        return sb.toString();
    }

    private static void appendPhase(StringBuilder sb, String phase, long ms) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(phase).append('=').append(formatSeconds(ms));
    }

    public static String formatSeconds(long ms) {
        return String.format("%.1fs", ms / 1000.0);
    }

    /** Drops all recorded phases for this scan. Call once the [Timing] summary has been logged. */
    public void clear(String scanKey) {
        if (scanKey != null) {
            timings.remove(scanKey);
        }
    }
}
