package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
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
 * Discovers affected IDs with the querybatch endpoint (POST /v1/querybatch) —
 * repeated single-item calls to /v1/query are intentionally avoided.
 * Fetches deduplicated advisory details within a bounded per-call request/time budget.
 *
 * Air-gapped mode: when constructed with a snapshot store and the air-gapped flag,
 * queries are answered from the offline snapshot instead of HTTP (components absent
 * from the snapshot remain unresolved).
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
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    /** Called once by Spring config after construction to enable external-API metrics. */
    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

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
            String cweId,
            com.salkcoding.oswl.domain.enums.RiskLevel severity,
            Double cvssScore,
            String cvssVector) {
        public OsvVuln(String osvId, String cveId, String summary, String fixVersion, String cweId) {
            this(osvId, cveId, summary, fixVersion, cweId, null, null, null);
        }
        public com.salkcoding.oswl.domain.enums.RiskLevel effectiveSeverity() {
            if (osvId != null && osvId.startsWith("MAL-")) return com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL;
            return severity != null ? severity : com.salkcoding.oswl.domain.enums.RiskLevel.NONE;
        }
    }

    /** Result set for a single query, aligned with the input batch index. */
    public record OsvResult(List<OsvVuln> vulns, boolean resolved) {
        public OsvResult(List<OsvVuln> vulns) { this(vulns, true); }
        public static OsvResult unresolved() { return new OsvResult(List.of(), false); }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Sends components in batches of up to 1,000 and returns results aligned with the input list.
     * Only a resolved empty result means the lookup returned no findings; failures remain unresolved.
     */
    public List<OsvResult> queryBatch(List<OsvQuery> queries) {
        if (queries.isEmpty()) return Collections.emptyList();

        if (airgapped) {
            return queryBatchFromSnapshot(queries);
        }

        List<OsvResult> allResults = new ArrayList<>(queries.size());

        DetailBudget details = new DetailBudget();
        for (int i = 0; i < queries.size(); i += MAX_BATCH_SIZE) {
            List<OsvQuery> chunk = queries.subList(i, Math.min(i + MAX_BATCH_SIZE, queries.size()));
            allResults.addAll(doQueryBatch(chunk, details));
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
        Set<String> unresolved = snapshotService.findUnresolvedKeys(distinctKeys);

        List<OsvResult> results = new ArrayList<>(queries.size());
        int hits = 0;
        for (String key : keys) {
            List<SnapshotVuln> vulns = key != null ? found.get(key) : null;
            if (vulns == null) {
                results.add(OsvResult.unresolved());
            } else {
                hits++;
                results.add(new OsvResult(vulns.stream()
                        .map(v -> new OsvVuln(v.osvId(), v.cveId(), v.summary(), v.fixVersion(), v.cweId(), risk(v.severity(), v.cvssScore()), v.cvssScore(), v.cvss3Vector()))
                        .toList(), !unresolved.contains(key)));
            }
        }
        log.debug("[OsvClient] air-gapped querybatch size={} snapshotHits={} totalVulns={}",
                queries.size(), hits,
                results.stream().mapToInt(r -> r.vulns().size()).sum());
        return results;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    public Set<String> findUnresolvedComponentKeys(java.util.Collection<String> keys) {
        return airgapped ? snapshotService.findUnresolvedKeys(keys) : Set.of();
    }

    @SuppressWarnings("unchecked")
    private List<OsvResult> doQueryBatch(List<OsvQuery> queries, DetailBudget details) {
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
                return Collections.nCopies(queries.size(), OsvResult.unresolved());
            }

            log.debug("[OsvClient] querybatch request size={} valid={}",
                    queries.size(), validIndices.size());

            Map<String, Object> response = restClient.post()
                    .uri("/v1/querybatch")
                    .header("Content-Type", "application/json")
                    .body(Map.of("queries", requestBody))
                    .retrieve()
                    .body(Map.class);
            recordApiCall(OswlMetrics.OUTCOME_SUCCESS);

            if (response == null || !(response.get("results") instanceof List<?> rawResults)) {
                log.debug("[OsvClient] querybatch response is empty or missing the 'results' key");
                return Collections.nCopies(queries.size(), OsvResult.unresolved());
            }

            List<OsvResult> parsed = new ArrayList<>(rawResults.size());

            for (Object rawResult : rawResults) {
                if (parsed.size() >= validIndices.size()) break;
                OsvQuery query = queries.get(validIndices.get(parsed.size()));
                if (!(rawResult instanceof Map<?, ?> resultMap)) {
                    parsed.add(OsvResult.unresolved());
                    continue;
                }
                Object vulnsObj = resultMap.get("vulns");
                if (vulnsObj != null && !(vulnsObj instanceof List<?>)) {
                    parsed.add(OsvResult.unresolved());
                    continue;
                }
                if (!(vulnsObj instanceof List<?> vulnList) || vulnList.isEmpty()) {
                    parsed.add(new OsvResult(List.of(), !resultMap.containsKey("next_page_token")));
                    continue;
                }
                List<OsvVuln> vulns = new ArrayList<>();
                boolean resolved = !resultMap.containsKey("next_page_token");
                for (Object vulnObj : vulnList) {
                    if (vulnObj instanceof Map<?, ?> vuln && vuln.get("id") instanceof String id && !id.isBlank()) {
                        Map<String, Object> detail = loadDetail(id, details);
                        vulns.add(parseVuln(detail != null ? detail : (Map<String, Object>) vuln, query));
                        if (detail == null) resolved = false;
                    } else resolved = false;
                }
                parsed.add(new OsvResult(vulns, resolved));
            }
            log.debug("[OsvClient] querybatch parsed results count={} totalVulns={}",
                    parsed.size(),
                    parsed.stream().mapToInt(r -> r.vulns().size()).sum());

            // Expand to queries.size() to preserve alignment and insert empty results for null versions
            OsvResult[] finalResults = new OsvResult[queries.size()];
            java.util.Arrays.fill(finalResults, OsvResult.unresolved());
            for (int i = 0; i < validIndices.size() && i < parsed.size(); i++) {
                finalResults[validIndices.get(i)] = parsed.get(i);
            }
            for (int i = 0; i < finalResults.length; i++) {
                OsvQuery q = queries.get(i);
                log.debug("[OsvClient] querybatch result[{}] {}:{} vulns={}", i, q.name(), q.version(), finalResults[i].vulns());
            }
            return java.util.Arrays.asList(finalResults);
        } catch (RestClientException e) {
            recordApiCall(isRateLimited(e) ? OswlMetrics.OUTCOME_RATE_LIMITED : OswlMetrics.OUTCOME_FAILURE);
            log.error("[OsvClient] querybatch failed: {}", e.getMessage());
            return Collections.nCopies(queries.size(), OsvResult.unresolved());
        }
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("osv", outcome);
        }
    }

    /** A 429 (or 403, used by some hosts as a throttle signal) anywhere in the error message. */
    private static boolean isRateLimited(RestClientException e) {
        String message = e.getMessage();
        return message != null && (message.contains("429") || message.contains("403"));
    }

    OsvVuln parseVuln(Map<String, Object> vuln) { return parseVuln(vuln, null); }

    OsvVuln parseVuln(Map<String, Object> vuln, OsvQuery query) {
        String osvId  = (String) vuln.get("id");
        String summary = (String) vuln.get("summary");

        // Extract the CVE ID from aliases
        String cveId = osvId != null && osvId.startsWith("CVE-") ? osvId : null;
        Object aliasesObj = vuln.get("aliases");
        if (aliasesObj instanceof List<?> aliases) {
            cveId = aliases.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(a -> a.startsWith("CVE-"))
                    .findFirst()
                    .orElse(cveId);
        }

        // Extract the fixed version from affected[].ranges[].events[fixed]
        String fixVersion = null;
        Set<String> fixedVersions = new LinkedHashSet<>();
        int rangeCount = 0;
        int introducedCount = 0;
        boolean openEnded = false;
        Object affectedObj = vuln.get("affected");
        if (affectedObj instanceof List<?> affected) {
            for (Object aff : affected) {
                if (!(aff instanceof Map<?, ?> affMap)) continue;
                if (query != null && (!(affMap.get("package") instanceof Map<?, ?> pkg)
                        || !query.name().equals(pkg.get("name")) || !query.ecosystem().equals(pkg.get("ecosystem")))) continue;
                Object rangesObj = affMap.get("ranges");
                if (!(rangesObj instanceof List<?> ranges)) continue;
                for (Object range : ranges) {
                    if (!(range instanceof Map<?, ?> rangeMap)) continue;
                    if ("GIT".equals(rangeMap.get("type"))) continue;
                    Object eventsObj = rangeMap.get("events");
                    if (!(eventsObj instanceof List<?> events)) continue;
                    rangeCount++;
                    boolean open = false;
                    for (Object event : events) {
                        if (!(event instanceof Map<?, ?> eventMap)) continue;
                        if (eventMap.containsKey("introduced")) { introducedCount++; open = true; }
                        Object fixed = eventMap.get("fixed");
                        if (fixed instanceof String fs && !fs.isBlank()) {
                            fixedVersions.add(fs);
                            open = false;
                        }
                    }
                    openEnded |= open;
                }
            }
        }

        // Ecosystem-specific ordering is needed to choose among disjoint ranges. Never
        // recommend an earlier fix when the advisory includes a later vulnerable interval.
        if (rangeCount == 1 && introducedCount <= 1 && !openEnded && fixedVersions.size() == 1)
            fixVersion = fixedVersions.iterator().next();
        if (query != null && affectedObj instanceof List<?> affected) {
            String matchedFix = fixForInstalledVersion(affected, query);
            if (matchedFix != null) fixVersion = matchedFix;
        }

        Double score = null; String vector = null;
        if (vuln.get("severity") instanceof List<?> severities) {
            for (String prefix : List.of("CVSS:4.0/", "CVSS:3.")) {
                for (Object raw : severities) {
                    if (!(raw instanceof Map<?, ?> entry) || !(entry.get("score") instanceof String value) || !value.startsWith(prefix)) continue;
                    Double parsed = value.startsWith("CVSS:4.0/")
                            ? com.salkcoding.oswl.service.cvss.CvssV4Calculator.baseScore(value)
                            : com.salkcoding.oswl.service.cvss.CvssV3Calculator.baseScore(value);
                    if (parsed != null && (score == null || parsed > score)) { score = parsed; vector = value; }
                }
                if (score != null) break;
            }
        }
        String severity = vuln.get("database_specific") instanceof Map<?, ?> db && db.get("severity") instanceof String value ? value : null;
        return new OsvVuln(osvId, cveId, summary, fixVersion, extractCweId(vuln), risk(severity, score), score, vector);
    }

    private record VersionInterval(String introduced, String fixed) {}

    /** Select a fix in the installed release's interval, never a fix from an older release line. */
    private static String fixForInstalledVersion(List<?> affected, OsvQuery query) {
        if (!numericRelease(query.version())) return null;
        List<VersionInterval> intervals = new ArrayList<>();
        for (Object raw : affected) {
            if (!(raw instanceof Map<?, ?> entry) || !(entry.get("package") instanceof Map<?, ?> pkg)
                    || !query.name().equals(pkg.get("name")) || !query.ecosystem().equals(pkg.get("ecosystem"))
                    || !(entry.get("ranges") instanceof List<?> ranges)) continue;
            for (Object rawRange : ranges) {
                if (!(rawRange instanceof Map<?, ?> range) || "GIT".equals(range.get("type"))
                        || !(range.get("events") instanceof List<?> events)) continue;
                String introduced = null;
                for (Object rawEvent : events) {
                    if (!(rawEvent instanceof Map<?, ?> event)) continue;
                    if (event.get("introduced") instanceof String value) introduced = value;
                    if (event.containsKey("last_affected") || event.containsKey("limit")) return null;
                    if (event.get("fixed") instanceof String fixed && introduced != null) {
                        if (!numericRelease(introduced) || !numericRelease(fixed)) return null;
                        intervals.add(new VersionInterval(introduced, fixed));
                        introduced = null;
                    }
                }
                if (introduced != null) {
                    if (!numericRelease(introduced)) return null;
                    intervals.add(new VersionInterval(introduced, null));
                }
            }
        }
        try {
            return intervals.stream().filter(i -> containsVersion(i, query.version()) && i.fixed() != null)
                    .map(VersionInterval::fixed)
                    .filter(candidate -> intervals.stream().noneMatch(i -> containsVersion(i, candidate)))
                    .min(com.salkcoding.oswl.vdb.SimpleVersionComparator::compare).orElse(null);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    private static boolean containsVersion(VersionInterval interval, String version) {
        return com.salkcoding.oswl.vdb.SimpleVersionComparator.compare(version, interval.introduced()) >= 0
                && (interval.fixed() == null || com.salkcoding.oswl.vdb.SimpleVersionComparator.compare(version, interval.fixed()) < 0);
    }

    private static boolean numericRelease(String value) {
        return value != null && value.matches("[vV]?[0-9]+(?:\\.[0-9]+)*");
    }

    private static final class DetailBudget {
        final Map<String, Map<String, Object>> cache = new java.util.HashMap<>();
        long deadline;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadDetail(String id, DetailBudget budget) {
        if (budget.cache.containsKey(id)) return budget.cache.get(id);
        if (budget.deadline == 0) budget.deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        if (budget.cache.size() >= 256 || System.nanoTime() >= budget.deadline || Thread.currentThread().isInterrupted()) return null;
        Map<String, Object> detail = null;
        try {
            var response = restClient.get().uri("/v1/vulns/{id}", id).retrieve().body(Map.class);
            if (response != null && id.equals(response.get("id"))) detail = response;
        } catch (RestClientException e) {
            recordApiCall(isRateLimited(e) ? OswlMetrics.OUTCOME_RATE_LIMITED : OswlMetrics.OUTCOME_FAILURE);
            log.warn("[OsvClient] advisory detail unavailable; retaining ID with incomplete coverage");
        }
        budget.cache.put(id, detail);
        return detail;
    }

    private static com.salkcoding.oswl.domain.enums.RiskLevel risk(String label, Double score) {
        var levels = com.salkcoding.oswl.domain.enums.RiskLevel.values();
        if (score != null && Double.isFinite(score) && score >= 0 && score <= 10)
            return levels[score >= 9 ? 0 : score >= 7 ? 1 : score >= 4 ? 2 : score > 0 ? 3 : 4];
        try { return label == null ? null : com.salkcoding.oswl.domain.enums.RiskLevel.valueOf(label.toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
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
