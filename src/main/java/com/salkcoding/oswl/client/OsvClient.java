package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the OSV.dev REST API.
 *
 * Uses the querybatch endpoint (POST /v1/querybatch) exclusively —
 * single-item looping over /v1/query is intentionally avoided.
 * One batch call can carry up to 1,000 queries with no rate limit.
 */
@Slf4j
@Component
public class OsvClient {

    private static final String BASE_URL = "https://api.osv.dev";
    private static final int MAX_BATCH_SIZE = 1000;

    private final RestClient restClient;

    public OsvClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    // ── DTOs ────────────────────────────────────────────────────────────

    /** Extracted vulnerability details from a single OSV vuln entry */
    public record OsvVuln(
            String osvId,
            String cveId,
            String summary,
            String fixVersion) {}

    /** Result set for one query (aligned with the input batch index) */
    public record OsvResult(List<OsvVuln> vulns) {}

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Sends components in batches of up to 1,000 and returns results aligned with the input list.
     * An empty OsvResult (vulns = []) means no vulnerabilities found for that component.
     */
    public List<OsvResult> queryBatch(List<OsvQuery> queries) {
        if (queries.isEmpty()) return Collections.emptyList();

        List<OsvResult> allResults = new ArrayList<>(queries.size());

        for (int i = 0; i < queries.size(); i += MAX_BATCH_SIZE) {
            List<OsvQuery> chunk = queries.subList(i, Math.min(i + MAX_BATCH_SIZE, queries.size()));
            allResults.addAll(doQueryBatch(chunk));
        }
        return allResults;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<OsvResult> doQueryBatch(List<OsvQuery> queries) {
        try {
            // Map.of() rejects null values — pre-filter queries with any null field
            // and track their original indices to re-align the result list.
            List<Integer> validIndices = new ArrayList<>();
            List<Map<String, Object>> requestBody = new ArrayList<>();
            for (int i = 0; i < queries.size(); i++) {
                OsvQuery q = queries.get(i);
                if (q.version() != null && q.name() != null && q.ecosystem() != null) {
                    validIndices.add(i);
                    requestBody.add(Map.of(
                            "version", q.version(),
                            "package", Map.of("name", q.name(), "ecosystem", q.ecosystem())));
                }
            }

            if (requestBody.isEmpty()) {
                return Collections.nCopies(queries.size(), new OsvResult(List.of()));
            }

            log.debug("[OsvClient] querybatch REQUEST size={} valid={} queries={}",
                    queries.size(), validIndices.size(), queries);

            Map<String, Object> response = restClient.post()
                    .uri("/v1/querybatch")
                    .header("Content-Type", "application/json")
                    .body(Map.of("queries", requestBody))
                    .retrieve()
                    .body(Map.class);

            log.debug("[OsvClient] querybatch RESPONSE raw={}", response);

            if (response == null || !response.containsKey("results")) {
                log.debug("[OsvClient] querybatch RESPONSE empty or missing 'results' key");
                return Collections.nCopies(queries.size(), new OsvResult(List.of()));
            }

            List<Object> rawResults = (List<Object>) response.get("results");
            List<OsvResult> parsed = new ArrayList<>(rawResults.size());

            for (Object rawResult : rawResults) {
                if (!(rawResult instanceof Map<?, ?> resultMap)) {
                    parsed.add(new OsvResult(List.of()));
                    continue;
                }
                Object vulnsObj = resultMap.get("vulns");
                if (!(vulnsObj instanceof List<?> vulnList) || vulnList.isEmpty()) {
                    parsed.add(new OsvResult(List.of()));
                    continue;
                }
                List<OsvVuln> vulns = new ArrayList<>();
                for (Object vulnObj : vulnList) {
                    if (vulnObj instanceof Map<?, ?> vuln) {
                        vulns.add(parseVuln((Map<String, Object>) vuln));
                    }
                }
                parsed.add(new OsvResult(vulns));
            }
            log.debug("[OsvClient] querybatch PARSED results count={} totalVulns={}",
                    parsed.size(),
                    parsed.stream().mapToInt(r -> r.vulns().size()).sum());

            // Re-expand to full queries.size(), placing empty results where version was null
            OsvResult[] finalResults = new OsvResult[queries.size()];
            java.util.Arrays.fill(finalResults, new OsvResult(List.of()));
            for (int i = 0; i < validIndices.size() && i < parsed.size(); i++) {
                finalResults[validIndices.get(i)] = parsed.get(i);
            }
            for (int i = 0; i < finalResults.length; i++) {
                OsvQuery q = queries.get(i);
                log.debug("[OsvClient] querybatch RESULT[{}] {}:{} vulns={}", i, q.name(), q.version(), finalResults[i].vulns());
            }
            return java.util.Arrays.asList(finalResults);
        } catch (RestClientException e) {
            log.error("[OsvClient] querybatch failed: {}", e.getMessage());
            return Collections.nCopies(queries.size(), new OsvResult(List.of()));
        }
    }

    private OsvVuln parseVuln(Map<String, Object> vuln) {
        String osvId  = (String) vuln.get("id");
        String summary = (String) vuln.get("summary");

        // Extract CVE ID from aliases
        String cveId = null;
        Object aliasesObj = vuln.get("aliases");
        if (aliasesObj instanceof List<?> aliases) {
            cveId = aliases.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(a -> a.startsWith("CVE-"))
                    .findFirst()
                    .orElse(null);
        }

        // Extract fix version from affected[].ranges[].events[fixed]
        String fixVersion = null;
        Object affectedObj = vuln.get("affected");
        outer:
        if (affectedObj instanceof List<?> affected) {
            for (Object aff : affected) {
                if (!(aff instanceof Map<?, ?> affMap)) continue;
                Object rangesObj = affMap.get("ranges");
                if (!(rangesObj instanceof List<?> ranges)) continue;
                for (Object range : ranges) {
                    if (!(range instanceof Map<?, ?> rangeMap)) continue;
                    Object eventsObj = rangeMap.get("events");
                    if (!(eventsObj instanceof List<?> events)) continue;
                    for (Object event : events) {
                        if (!(event instanceof Map<?, ?> eventMap)) continue;
                        Object fixed = eventMap.get("fixed");
                        if (fixed instanceof String fs && !fs.isBlank()) {
                            fixVersion = fs;
                            break outer;
                        }
                    }
                }
            }
        }

        return new OsvVuln(osvId, cveId, summary, fixVersion);
    }

    // ── Value types ───────────────────────────────────────────────────────

    public record OsvQuery(String ecosystem, String name, String version) {}
}
