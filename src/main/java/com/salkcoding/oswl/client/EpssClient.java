package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIRST.org EPSS scores (exploit probability). Best-effort; failures return empty maps.
 */
@Slf4j
@Component
public class EpssClient {

    private static final String BASE = "https://api.first.org/data/v1/epss";

    private final RestClient restClient = RestClient.create();

    /**
     * @param cveIds CVE- prefixed IDs (max ~50 per call)
     * @return map CVE ID → EPSS score (0.0–1.0)
     */
    public Map<String, Double> fetchScores(List<String> cveIds) {
        if (cveIds == null || cveIds.isEmpty()) return Map.of();
        String joined = String.join(",", cveIds.stream()
                .filter(id -> id != null && id.startsWith("CVE-"))
                .map(String::strip)
                .distinct()
                .limit(50)
                .toList());
        if (joined.isEmpty()) return Map.of();

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
