package com.salkcoding.oswl.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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

    private static final int MAX_PREVIEWS = 5;
    private static final int MAX_DETAIL_LINES = 8;

    private final ConcurrentHashMap<Long, Snapshot> snapshots = new ConcurrentHashMap<>();

    public void setStep(Long scanResultId, EnrichmentSubPhase subPhase, int step, String message) {
        if (scanResultId == null) return;
        int total = ENRICHMENT_STEPS;
        int percent = Math.min(100, Math.max(0, (int) Math.round((step * 100.0) / total)));
        snapshots.compute(scanResultId, (id, prev) -> {
            List<String> previews = prev != null ? prev.aiPreviews() : List.of();
            List<String> details = prev != null ? new ArrayList<>(prev.detailLines()) : new ArrayList<>();
            if (message != null && !message.isBlank()) {
                details.add(message.strip());
                if (details.size() > MAX_DETAIL_LINES) {
                    details = new ArrayList<>(details.subList(details.size() - MAX_DETAIL_LINES, details.size()));
                }
            }
            return new Snapshot(message, subPhase, step, total, percent, List.copyOf(details), previews);
        });
    }

    /** @deprecated prefer {@link #setStep} */
    public void set(Long scanResultId, String message) {
        setStep(scanResultId, EnrichmentSubPhase.CVE, 1, message);
    }

    public Snapshot getSnapshot(Long scanResultId) {
        Snapshot snap = snapshots.get(scanResultId);
        return snap != null ? snap : null;
    }

    public String get(Long scanResultId) {
        Snapshot snap = getSnapshot(scanResultId);
        return snap != null ? snap.message() : null;
    }

    public void addAiPreview(Long scanResultId, String previewLine) {
        if (scanResultId == null || previewLine == null || previewLine.isBlank()) return;
        snapshots.compute(scanResultId, (id, prev) -> {
            List<String> previews = prev != null ? new ArrayList<>(prev.aiPreviews()) : new ArrayList<>();
            previews.add(previewLine.strip());
            if (previews.size() > MAX_PREVIEWS) {
                previews = new ArrayList<>(previews.subList(previews.size() - MAX_PREVIEWS, previews.size()));
            }
            if (prev == null) {
                return new Snapshot(null, null, 0, ENRICHMENT_STEPS, 0, List.of(), List.copyOf(previews));
            }
            return new Snapshot(prev.message(), prev.subPhase(), prev.step(), prev.totalSteps(),
                    prev.percent(), prev.detailLines(), List.copyOf(previews));
        });
    }

    public List<String> getAiPreviews(Long scanResultId) {
        Snapshot snap = getSnapshot(scanResultId);
        return snap != null ? snap.aiPreviews() : List.of();
    }

    public void remove(Long scanResultId) {
        snapshots.remove(scanResultId);
    }
}
