package com.salkcoding.oswl.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry for AI enrichment progress (Quick Import live status).
 */
@Component
public class EnrichmentProgressHolder {

    public static final int ENRICHMENT_STEPS = 5;

    public enum EnrichmentSubPhase {
        CVE,
        LICENSE,
        POSTURE,
        TREND,
        DIFF
    }

    public record Snapshot(
            String message,
            EnrichmentSubPhase subPhase,
            int step,
            int totalSteps,
            int percent,
            List<String> detailLines,
            List<String> aiPreviews) {}

    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    public void setStep(Long scanResultId, EnrichmentSubPhase subPhase, int step, String message) {
        if (scanResultId == null) return;
        int total = ENRICHMENT_STEPS;
        int percent = Math.min(100, Math.max(0, (int) Math.round((step * 100.0) / total)));
        snapshots.compute(scanResultId, (id, prev) ->
                new Snapshot(message, subPhase, step, total, percent, List.of(), List.of()));
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
