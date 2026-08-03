package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API client for OSV.dev.
 *
 * Uses only the querybatch endpoint (POST /v1/querybatch) —
 * repeated single-item calls to /v1/query are intentionally avoided.
 * Up to 1,000 queries can be sent in a single batch call, with no rate limit.
 *
 * Air-gapped mode: when constructed with a snapshot store and the air-gapped flag,
 * queries are answered from the offline snapshot instead of HTTP (components absent
 * from the snapshot resolve as "no vulnerabilities").
 */
@Slf4j
public class OsvClient {

    private static final String BASE_URL = "https://api.osv.dev";
    private static final int MAX_BATCH_SIZE = 1000;
    /** Default timeouts used by the no-arg/2-arg constructors (unit tests, and any caller not wired through Spring config). */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** Generous — a 1,000-item querybatch against a slow connection legitimately takes a while. */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final AirgappedSnapshotService snapshotService;
    private final boolean airgapped;

    /** Live-HTTP client (no snapshot store). Used directly by unit tests. */
    public OsvClient() {
        this(null, false);
    }

    public OsvClient(AirgappedSnapshotService snapshotService, boolean airgapped) {
        this(snapshotService, airgapped, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public OsvClient(AirgappedSnapshotService snapshotService, boolean airgapped,
                     Duration connectTimeout, Duration readTimeout) {
        this.snapshotService = snapshotService;
        this.airgapped = airgapped && snapshotService != null;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/json")
                .requestFactory(requestFactory)
                .build();
        if (this.airgapped) {
            log.info("[OsvClient] Air-gapped mode — OSV queries served from the offline snapshot store, no outbound HTTP");
        }
    }

    // ── DTO ────────────────────────────────────────────────────────────

    /** Vulnerability details extracted from a single OSV vulnerability entry. */
    public record OsvVuln(
            String osvId,
            String cveId,
            String summary,
            String fixVersion,
            String cweId) {}

    /** Result set for a single query, aligned with the input batch index. */
    public record OsvResult(List<OsvVuln> vulns) {}

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Sends components in batches of up to 1,000 and returns results aligned with the input list.
     * An empty OsvResult(vulns = []) means the component has no vulnerabilities.
     */
    public List<OsvResult> queryBatch(List<OsvQuery> queries) {
        if (queries.isEmpty()) return Collections.emptyList();

        if (airgapped) {
            return queryBatchFromSnapshot(queries);
        }

        List<OsvResult> allResults = new ArrayList<>(queries.size());

        for (int i = 0; i < queries.size(); i += MAX_BATCH_SIZE) {
            List<OsvQuery> chunk = queries.subList(i, Math.min(i + MAX_BATCH_SIZE, queries.size()));
            allResults.addAll(doQueryBatch(chunk));
        }
        return allResults;
    }

    /** Offline path: answers every query from the snapshot store, preserving input alignment. */
    private List<OsvResult> queryBatchFromSnapshot(List<OsvQuery> queries) {
        List<String> keys = new ArrayList<>(queries.size());
        Set<String> distinctKeys = new LinkedHashSet<>();
        for (OsvQuery q : queries) {
            String key = q.version() != null && q.name() != null && q.ecosystem() != null
                    ? AirgappedSnapshotService.componentKey(q.ecosystem(), q.name(), q.version())
                    : null;
            keys.add(key);
            if (key != null) distinctKeys.add(key);
        }
        Map<String, List<SnapshotVuln>> found = snapshotService.findOsvVulns(distinctKeys);

        List<OsvResult> results = new ArrayList<>(queries.size());
        int hits = 0;
        for (String key : keys) {
            List<SnapshotVuln> vulns = key != null ? found.get(key) : null;
            if (vulns == null) {
                results.add(new OsvResult(List.of()));
            } else {
                hits++;
                results.add(new OsvResult(vulns.stream()
                        .map(v -> new OsvVuln(v.osvId(), v.cveId(), v.summary(), v.fixVersion(), v.cweId()))
                        .toList()));
            }
        }
        log.debug("[OsvClient] air-gapped querybatch size={} snapshotHits={} totalVulns={}",
                queries.size(), hits,
                results.stream().mapToInt(r -> r.vulns().size()).sum());
        return results;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<OsvResult> doQueryBatch(List<OsvQuery> queries) {
        try {
            // Map.of() rejects null values — pre-filter queries with null fields
            // and track original indices to realign the result list.
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

            log.debug("[OsvClient] querybatch request size={} valid={} queries={}",
                    queries.size(), validIndices.size(), queries);

            Map<String, Object> response = restClient.post()
                    .uri("/v1/querybatch")
                    .header("Content-Type", "application/json")
                    .body(Map.of("queries", requestBody))
                    .retrieve()
                    .body(Map.class);

            log.debug("[OsvClient] querybatch response raw={}", response);

            if (response == null || !response.containsKey("results")) {
                log.debug("[OsvClient] querybatch response is empty or missing the 'results' key");
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
            log.debug("[OsvClient] querybatch parsed results count={} totalVulns={}",
                    parsed.size(),
                    parsed.stream().mapToInt(r -> r.vulns().size()).sum());

            // Expand to queries.size() to preserve alignment and insert empty results for null versions
            OsvResult[] finalResults = new OsvResult[queries.size()];
            java.util.Arrays.fill(finalResults, new OsvResult(List.of()));
            for (int i = 0; i < validIndices.size() && i < parsed.size(); i++) {
                finalResults[validIndices.get(i)] = parsed.get(i);
            }
            for (int i = 0; i < finalResults.length; i++) {
                OsvQuery q = queries.get(i);
                log.debug("[OsvClient] querybatch result[{}] {}:{} vulns={}", i, q.name(), q.version(), finalResults[i].vulns());
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

        // Extract the CVE ID from aliases
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

        // Extract the fixed version from affected[].ranges[].events[fixed]
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

        return new OsvVuln(osvId, cveId, summary, fixVersion, extractCweId(vuln));
    }

    /**
     * Reads the first CWE from OSV {@code database_specific.cwe_ids} when present.
     * Package-visible for unit tests.
     */
    static String extractCweId(Map<String, Object> vuln) {
        Object dbSpec = vuln.get("database_specific");
        if (!(dbSpec instanceof Map<?, ?> spec)) {
            return null;
        }
        Object cweIds = spec.get("cwe_ids");
        if (!(cweIds instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.getFirst();
        if (!(first instanceof String raw) || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("CWE-")) {
            return trimmed;
        }
        if (trimmed.matches("\\d+")) {
            return "CWE-" + trimmed;
        }
        return trimmed;
    }

    // ── Value types ───────────────────────────────────────────────────────

    public record OsvQuery(String ecosystem, String name, String version) {}
}
