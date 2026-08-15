package com.salkcoding.oswl.service.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry for AI enrichment progress (Quick Import live status).
 *
 * <p>The enrichment phase owns the 55–100 band of the overall job progress — 55–80 for the
 * data pipeline (deps.dev/OSV fetch completions) and 80–100 for the AI blocks (sub-phase
 * completion count). Percent is clamped monotonically here so parallel reporters can never move
 * it backwards.
 *
 * <p>{@code detailLines} (what was found / batch progress) and {@code aiPreviews} (raw
 * free-form model output) are bounded ring buffers — unbounded growth would bloat every SSE
 * frame and leak memory.
 */
@Component
public class EnrichmentProgressHolder {

    /** Posture/trend/diff are folded into one combined-insights block, so 5 blocks became 3. */
    public static final int ENRICHMENT_STEPS = 3;

    /** Data pipeline (deps.dev fetch) maps to 55–80 of the overall job progress. */
    private static final int DATA_PERCENT_BASE = 55;
    private static final int DATA_PERCENT_SPAN = 25;
    /** AI blocks (sub-phase completion count / {@link #ENRICHMENT_STEPS}) map to 80–100. */
    private static final int AI_PERCENT_BASE = 80;
    private static final int AI_PERCENT_SPAN = 20;

    /** Rolling AI preview keeps only the tail — older text is dropped. */
    private static final int PREVIEW_CHAR_LIMIT = 2_000;
    /** Detail log keeps only the most recent lines. */
    private static final int DETAIL_LINE_LIMIT = 20;

    public enum EnrichmentSubPhase {
        CVE,
        LICENSE,
        /** Posture + security-trend + license-trend + version-diff folded into one call. */
        INSIGHTS
    }

    public record Snapshot(
            String message,
            EnrichmentSubPhase subPhase,
            int step,
            int totalSteps,
            int percent,
            List<String> detailLines,
            List<String> aiPreviews,
            Integer cacheTotal,
            Integer cacheHit,
            Integer cacheToFetch) {}

    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    /**
     * Invoked (outside the map lock) with the scan id after every snapshot mutation.
     * QuickImportService registers here to throttle SSE pushes on high-frequency updates
     * (streaming previews, per-fetch progress).
     */
    private volatile Consumer<Long> updateListener;

    public void setUpdateListener(Consumer<Long> updateListener) {
        this.updateListener = updateListener;
    }

    public void setStep(Long scanResultId, EnrichmentSubPhase subPhase, int step, String message) {
        if (scanResultId == null) return;
        int aiPercent = AI_PERCENT_BASE + (int) Math.round(step * (double) AI_PERCENT_SPAN / ENRICHMENT_STEPS);
        snapshots.compute(scanResultId, (id, prev) -> new Snapshot(
                message, subPhase, step, ENRICHMENT_STEPS,
                Math.max(prev != null ? prev.percent() : 0, aiPercent),
                prev != null ? prev.detailLines() : List.of(),
                prev != null ? prev.aiPreviews() : List.of(),
                prev != null ? prev.cacheTotal() : null,
                prev != null ? prev.cacheHit() : null,
                prev != null ? prev.cacheToFetch() : null));
        fireUpdate(scanResultId);
    }

    /**
     * Records the cache decision (total / cacheHit / toFetch) so the job status can show a
     * cache badge, and seeds the data-phase progress total. With nothing to fetch the data
     * phase is effectively instant, so the percent jumps straight to the AI band base.
     */
    public void recordCacheStats(Long scanResultId, int total, int cacheHit, int toFetch) {
        if (scanResultId == null) return;
        snapshots.compute(scanResultId, (id, prev) -> new Snapshot(
                prev != null ? prev.message() : null,
                prev != null ? prev.subPhase() : null,
                prev != null ? prev.step() : 0,
                ENRICHMENT_STEPS,
                Math.max(prev != null ? prev.percent() : 0, toFetch == 0 ? AI_PERCENT_BASE : DATA_PERCENT_BASE),
                prev != null ? prev.detailLines() : List.of(),
                prev != null ? prev.aiPreviews() : List.of(),
                total, cacheHit, toFetch));
        fireUpdate(scanResultId);
    }

    /**
     * A deps.dev fetch completed. {@code fetchedCount} is measured against the
     * {@link #recordCacheStats} toFetch total; percent is clamped monotonically.
     */
    public void reportDataProgress(Long scanResultId, int fetchedCount) {
        if (scanResultId == null) return;
        snapshots.compute(scanResultId, (id, prev) -> {
            if (prev == null || prev.cacheToFetch() == null || prev.cacheToFetch() <= 0) {
                return prev; // no total to measure against (e.g. all cache hits)
            }
            int pct = DATA_PERCENT_BASE + (int) Math.round(
                    Math.min(fetchedCount, prev.cacheToFetch()) * (double) DATA_PERCENT_SPAN / prev.cacheToFetch());
            int clamped = Math.max(prev.percent(), pct);
            if (clamped == prev.percent()) return prev;
            return new Snapshot(prev.message(), prev.subPhase(), prev.step(), prev.totalSteps(), clamped,
                    prev.detailLines(), prev.aiPreviews(),
                    prev.cacheTotal(), prev.cacheHit(), prev.cacheToFetch());
        });
        fireUpdate(scanResultId);
    }

    /** Appends a raw AI output delta to the rolling preview (tail-capped, single entry). */
    public void appendPreview(Long scanResultId, String chunk) {
        if (scanResultId == null || chunk == null || chunk.isEmpty()) return;
        snapshots.compute(scanResultId, (id, prev) -> {
            String current = prev != null && !prev.aiPreviews().isEmpty() ? prev.aiPreviews().getFirst() : "";
            String next = current + chunk;
            if (next.length() > PREVIEW_CHAR_LIMIT) {
                next = next.substring(next.length() - PREVIEW_CHAR_LIMIT);
            }
            return new Snapshot(
                    prev != null ? prev.message() : null,
                    prev != null ? prev.subPhase() : null,
                    prev != null ? prev.step() : 0,
                    ENRICHMENT_STEPS,
                    prev != null ? prev.percent() : 0,
                    prev != null ? prev.detailLines() : List.of(),
                    List.of(next),
                    prev != null ? prev.cacheTotal() : null,
                    prev != null ? prev.cacheHit() : null,
                    prev != null ? prev.cacheToFetch() : null);
        });
        fireUpdate(scanResultId);
    }

    /**
     * Appends one line to the detail log (ring buffer — oldest lines drop off).
     * Content rule: library name/version/CVE counts only, never repo URLs or paths.
     */
    public void appendDetail(Long scanResultId, String line) {
        if (scanResultId == null || line == null || line.isBlank()) return;
        snapshots.compute(scanResultId, (id, prev) -> {
            List<String> lines = new ArrayList<>(prev != null ? prev.detailLines() : List.of());
            lines.add(line);
            while (lines.size() > DETAIL_LINE_LIMIT) {
                lines.removeFirst();
            }
            return new Snapshot(
                    prev != null ? prev.message() : null,
                    prev != null ? prev.subPhase() : null,
                    prev != null ? prev.step() : 0,
                    ENRICHMENT_STEPS,
                    prev != null ? prev.percent() : 0,
                    List.copyOf(lines),
                    prev != null ? prev.aiPreviews() : List.of(),
                    prev != null ? prev.cacheTotal() : null,
                    prev != null ? prev.cacheHit() : null,
                    prev != null ? prev.cacheToFetch() : null);
        });
        fireUpdate(scanResultId);
    }

    private void fireUpdate(Long scanResultId) {
        Consumer<Long> listener = updateListener;
        if (listener == null) return;
        try {
            listener.accept(scanResultId);
        } catch (Exception e) {
            // A listener (SSE push) failure must never break the enrichment pipeline.
        }
    }

    public Snapshot getSnapshot(Long scanResultId) {
        Snapshot snap = snapshots.get(scanResultId);
        return snap != null ? snap : null;
    }

    public String get(Long scanResultId) {
        Snapshot snap = getSnapshot(scanResultId);
        return snap != null ? snap.message() : null;
    }

    public void remove(Long scanResultId) {
        snapshots.remove(scanResultId);
    }
}
