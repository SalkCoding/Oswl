package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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

    /** Live-HTTP client (no snapshot store). Used directly by unit tests. */
    public EpssClient() {
        this(null, false);
    }

    public EpssClient(AirgappedSnapshotService snapshotService, boolean airgapped) {
        this.snapshotService = snapshotService;
        this.airgapped = airgapped && snapshotService != null;
        if (this.airgapped) {
            log.info("[EPSS] Air-gapped mode — EPSS scores served from the offline snapshot store, no outbound HTTP");
        }
    }

    /**
     * @param cveIds CVE- prefixed IDs (max ~50 per call)
     * @return map CVE ID → EPSS score (0.0–1.0)
     */
    public Map<String, Double> fetchScores(List<String> cveIds) {
        if (cveIds == null || cveIds.isEmpty()) return Map.of();
        List<String> ids = cveIds.stream()
                .filter(id -> id != null && id.startsWith("CVE-"))
                .map(String::strip)
                .distinct()
                .limit(50)
                .toList();
        if (ids.isEmpty()) return Map.of();

        if (airgapped) {
            Map<String, Double> result = snapshotService.findEpssScores(ids);
            log.debug("[EPSS] air-gapped fetch requested={} snapshotHits={}", ids.size(), result.size());
            return result;
        }

        String joined = String.join(",", ids);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(BASE + "?cve=" + joined)
                    .retrieve()
                    .body(Map.class);
            if (body == null) return Map.of();
            Object data = body.get("data");
            if (!(data instanceof List<?> rows)) return Map.of();

            Map<String, Double> result = new LinkedHashMap<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Object cve = map.get("cve");
                    Object epss = map.get("epss");
                    if (cve != null && epss != null) {
                        try {
                            result.put(cve.toString().strip().toUpperCase(),
                                    Double.parseDouble(epss.toString()));
                        } catch (NumberFormatException ignored) {
                            // skip malformed row
                        }
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[EPSS] Batch fetch failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
