package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * FIRST.org EPSS scores (exploit probability). Best-effort; failures return empty maps.
 *
 * Air-gapped mode: when constructed with a snapshot store and the air-gapped flag,
 * scores are answered from the offline snapshot instead of HTTP (CVEs absent from
 * the snapshot are simply omitted, matching live API semantics).
 */
@Slf4j
public class EpssClient {

    private static final String BASE = "https://api.first.org/data/v1/epss";

    private final RestClient restClient = RestClient.create();
    private final AirgappedSnapshotService snapshotService;
    private final boolean airgapped;
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    /** Called once by Spring config after construction to enable external-API metrics. */
    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

    /** Live-HTTP client (no snapshot store). Used directly by unit tests. */
    public EpssClient() {
        this(null, false);
    }

    public EpssClient(AirgappedSnapshotService snapshotService, boolean airgapped) {
        this.snapshotService = snapshotService;
        if (airgapped && snapshotService == null)
            throw new IllegalArgumentException("Air-gapped mode requires a snapshot service");
        this.airgapped = airgapped;
        if (this.airgapped) {
            log.info("[EPSS] Air-gapped mode — EPSS scores served from the offline snapshot store, no outbound HTTP");
        }
    }

    /**
     * @param cveIds CVE- prefixed IDs; online requests are split into batches of 50
     * @return map CVE ID → EPSS score (0.0–1.0)
     */
    public Map<String, Double> fetchScores(List<String> cveIds) {
        if (cveIds == null || cveIds.isEmpty()) return Map.of();
        List<String> ids = cveIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> id.strip().toUpperCase(java.util.Locale.ROOT))
                .filter(NvdClient::isValidCveId)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();

        if (airgapped) {
            Map<String, Double> result = snapshotService.readEpssSnapshot(ids);
            if (result == null) return Map.of();
            log.debug("[EPSS] air-gapped fetch requested={} snapshotHits={}", ids.size(), result.size());
            return result;
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (int start = 0; start < ids.size(); start += 50) {
            if (Thread.currentThread().isInterrupted()) break;
            scores.putAll(fetchBatch(ids.subList(start, Math.min(start + 50, ids.size()))));
        }
        return scores;
    }

    private Map<String, Double> fetchBatch(List<String> ids) {
        String joined = String.join(",", ids);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(BASE + "?cve=" + joined)
                    .retrieve()
                    .body(Map.class);
            recordApiCall(OswlMetrics.OUTCOME_SUCCESS);
            if (body == null) return Map.of();
            Object data = body.get("data");
            if (!(data instanceof List<?> rows)) return Map.of();

            Map<String, Double> result = new LinkedHashMap<>();
            var unresolved = new HashSet<String>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Object cve = map.get("cve");
                    Object epss = map.get("epss");
                    if (cve instanceof String cveId) {
                        String key = cveId.strip().toUpperCase(java.util.Locale.ROOT);
                        if (!ids.contains(key) || unresolved.contains(key)) continue;
                        try {
                            if (epss == null) throw new NumberFormatException("Missing EPSS score");
                            double score = Double.parseDouble(epss.toString());
                            if (!Double.isFinite(score) || score < 0 || score > 1)
                                throw new NumberFormatException("Invalid EPSS probability");
                            Double previous = result.putIfAbsent(key, score);
                            if (previous != null && previous.doubleValue() != score)
                                throw new NumberFormatException("Conflicting EPSS scores");
                        } catch (NumberFormatException ignored) {
                            result.remove(key);
                            unresolved.add(key);
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            recordApiCall(OswlMetrics.OUTCOME_FAILURE);
            log.warn("[EPSS] Batch fetch failed: {}", e.getMessage());
            return Map.of();
        }
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("epss", outcome);
        }
    }
}
