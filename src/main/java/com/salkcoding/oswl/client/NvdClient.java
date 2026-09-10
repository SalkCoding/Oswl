package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NVD 2.0 REST API client.
 *
 * <p>Supports both authenticated (API key) and unauthenticated calls. NVD rate-limits requests
 * per rolling window, so the client enforces a minimum interval between calls and backs off
 * exponentially when it receives a 403/429 response.
 *
 * <p>Air-gapped mode: queries are answered from the offline snapshot store keyed by component
 * ({@code ECOSYSTEM|name|version}) instead of live NVD HTTP calls.
 */
@Slf4j
public class NvdClient {

    private static final String BASE_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    private static final int MAX_RETRIES = 3;
    private static final int MAX_PAGES = 20;
    private static final java.util.regex.Pattern CVE_ID = java.util.regex.Pattern.compile("CVE-[0-9]{4}-[0-9]{4,}");
    private static final long UNAUTH_INTERVAL_MS = 6_500;
    private static final long AUTH_INTERVAL_MS = 650;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);

    private final RestClient restClient;
    private final AirgappedSnapshotService snapshotService;
    private final boolean airgapped;
    private final String apiKey;
    private final long minIntervalMs;
    private long lastRequestMs = 0;
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    /** Called once by Spring config after construction to enable external-API metrics. */
    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

    /** Live-HTTP client with no API key. Used directly by unit tests. */
    public NvdClient() {
        this(null, false, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public NvdClient(AirgappedSnapshotService snapshotService, boolean airgapped, String apiKey,
                     Duration connectTimeout, Duration readTimeout) {
        this.snapshotService = snapshotService;
        if (airgapped && snapshotService == null)
            throw new IllegalArgumentException("Air-gapped mode requires a snapshot service");
        this.airgapped = airgapped;
        this.apiKey = (apiKey != null) ? apiKey.strip() : null;
        this.minIntervalMs = (this.apiKey != null && !this.apiKey.isBlank()) ? AUTH_INTERVAL_MS : UNAUTH_INTERVAL_MS;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        if (this.airgapped) {
            log.info("[NVD] Air-gapped mode — NVD queries served from the offline snapshot store, no outbound HTTP");
        }
    }

    /** One NVD CVE returned for a CPE lookup. */
    public record NvdCve(String cveId, String description, RiskLevel severity,
                         Double cvssScore, String cvss3Vector, MatchConfidence matchConfidence) {
        public NvdCve {
            cvssScore = com.salkcoding.oswl.service.cvss.CvssScore.validOrNull(cvssScore);
        }
    }

    public static final class IncompleteLookupException extends IllegalStateException {
        private final List<NvdCve> findings;

        private IncompleteLookupException(List<NvdCve> findings) {
            super("NVD lookup unavailable");
            this.findings = List.copyOf(findings);
        }

        public List<NvdCve> findings() { return findings; }
    }

    /**
     * Queries live NVD by exact CPE name. Returns an empty list when the CPE is blank,
     * or the client is air-gapped. Failed/malformed lookups throw so callers retain incomplete coverage.
     */
    public List<NvdCve> findByCpeName(String cpeName, MatchConfidence confidence) {
        if (airgapped || cpeName == null || cpeName.isBlank()) {
            return List.of();
        }
        String url = BASE_URL + "?cpeName=" + URLEncoder.encode(cpeName.strip(), StandardCharsets.UTF_8);
        Map<String, NvdCve> found = new LinkedHashMap<>();
        try {
            Long expectedTotal = null;
            long offset = 0;
            for (int page = 0; page < MAX_PAGES; page++) {
                Map<String, Object> body = doRequest(offset == 0 ? url : url + "&startIndex=" + offset);
                List<NvdCve> findings = offset == 0 ? parseBody(body, confidence) : parseBody(body, confidence, offset);
                boolean duplicate = false;
                for (NvdCve finding : findings) {
                    duplicate |= found.putIfAbsent(finding.cveId(), finding) != null;
                }
                Long total = nonNegativeInteger(body.get("totalResults"));
                if (duplicate || expectedTotal != null && !expectedTotal.equals(total)) {
                    throw new IncompleteLookupException(new ArrayList<>(found.values()));
                }
                expectedTotal = total;
                offset += findings.size();
                if (offset == total) return List.copyOf(found.values());
            }
            throw new IncompleteLookupException(new ArrayList<>(found.values()));
        } catch (IncompleteLookupException e) {
            for (NvdCve finding : e.findings()) found.putIfAbsent(finding.cveId(), finding);
            log.warn("[NVD] Incomplete CPE lookup; retaining {} findings", found.size());
            throw new IncompleteLookupException(new ArrayList<>(found.values()));
        } catch (Exception e) {
            log.warn("[NVD] CPE lookup failed for {}: {}", cpeName, e.getMessage());
            if (!found.isEmpty()) throw new IncompleteLookupException(new ArrayList<>(found.values()));
            throw new IllegalStateException("NVD lookup unavailable", e);
        }
    }

    public boolean isAirgapped() { return airgapped; }

    public boolean isSnapshotCoverageUncertain() {
        return airgapped && snapshotService.isSourceStaleOrUndated(AirgappedSnapshotService.SOURCE_NVD);
    }

    /**
     * Offline path: looks up NVD-derived CVEs by component key.
     * Returns stored findings by component key; absent keys have no completed lookup evidence.
     */
    public Map<String, List<NvdCve>> findByComponentKeys(Collection<String> componentKeys) {
        if (!airgapped || componentKeys == null || componentKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AirgappedSnapshotService.SnapshotVuln>> found =
                snapshotService.findNvdVulns(componentKeys);
        Map<String, List<NvdCve>> result = new LinkedHashMap<>();
        for (String key : componentKeys) {
            List<AirgappedSnapshotService.SnapshotVuln> vulns = found.get(key);
            if (vulns == null) {
                continue;
            } else {
                result.put(key, vulns.stream()
                        .map(v -> new NvdCve(v.cveId(), v.summary(),
                                parseSeverity(v.severity()), v.cvssScore(), v.cvss3Vector(),
                                parseConfidence(v.matchConfidence())))
                        .toList());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doRequest(String url) {
        int attempt = 0;
        while (true) {
            throttle();
            try {
                Map<String, Object> body = restClient.get()
                        .uri(java.net.URI.create(url))
                        .header("Accept", "application/json")
                        .headers(headers -> {
                            if (apiKey != null && !apiKey.isBlank()) {
                                headers.add("apiKey", apiKey);
                            } else {
                                headers.add("User-Agent", "OsWL-NvdClient/1.0");
                            }
                        })
                        .retrieve()
                        .body(Map.class);
                recordApiCall(OswlMetrics.OUTCOME_SUCCESS);
                return body != null ? body : Map.of();
            } catch (RestClientException e) {
                recordApiCall(isRateLimited(e) ? OswlMetrics.OUTCOME_RATE_LIMITED : OswlMetrics.OUTCOME_FAILURE);
                if (!isRateLimited(e) || attempt >= MAX_RETRIES) {
                    throw e;
                }
                long backoff = minIntervalMs * (1L << attempt);
                log.debug("[NVD] Rate limited — backing off {}ms before retry {}/{}", backoff, attempt + 1, MAX_RETRIES);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RestClientException("Interrupted during NVD rate-limit backoff", ie);
                }
                attempt++;
            }
        }
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRequestMs;
        if (elapsed < minIntervalMs && lastRequestMs > 0) {
            try {
                Thread.sleep(minIntervalMs - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestMs = System.currentTimeMillis();
    }

    private static boolean isRateLimited(RestClientException e) {
        String message = e.getMessage();
        return message != null && (message.contains("403") || message.contains("429"));
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("nvd", outcome);
        }
    }

    private List<NvdCve> parseBody(Map<String, Object> body, MatchConfidence confidence) {
        return parseBody(body, confidence, 0);
    }

    @SuppressWarnings("unchecked")
    private List<NvdCve> parseBody(Map<String, Object> body, MatchConfidence confidence, long expectedStart) {
        if (body == null) throw new IllegalArgumentException("Missing NVD response");
        Object vulns = body.get("vulnerabilities");
        if (!(vulns instanceof List<?> list)) throw new IllegalArgumentException("Missing NVD vulnerabilities");
        Long total = nonNegativeInteger(body.get("totalResults"));
        Long start = nonNegativeInteger(body.get("startIndex"));
        Long pageSize = nonNegativeInteger(body.get("resultsPerPage"));
        boolean incomplete = total == null || total < expectedStart + list.size()
                || start == null || start != expectedStart || pageSize == null || pageSize < list.size()
                || list.isEmpty() && total != null && expectedStart < total;
        List<NvdCve> result = new ArrayList<>();
        for (Object item : list) {
            try {
                if (!(item instanceof Map<?, ?> v)) throw new IllegalArgumentException("Invalid NVD item");
                Map<String, Object> cve = (Map<String, Object>) v.get("cve");
                if (cve == null) throw new IllegalArgumentException("Missing NVD CVE");
                String id = (String) cve.get("id");
                if (id == null || !CVE_ID.matcher(id).matches())
                    throw new IllegalArgumentException("Invalid NVD identifier");
                Cvss cvss = extractCvss(cve);
                result.add(new NvdCve(id, extractDescription(cve), cvss.severity, cvss.score, cvss.vector, confidence));
            } catch (RuntimeException e) {
                log.debug("[NVD] Skipping malformed finding; lookup remains incomplete", e);
                incomplete = true;
            }
        }
        if (incomplete) throw new IncompleteLookupException(result);
        return result;
    }

    private static Long nonNegativeInteger(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) return null;
        long number = ((Number) value).longValue();
        return number >= 0 ? number : null;
    }

    @SuppressWarnings("unchecked")
    private static String extractDescription(Map<String, Object> cve) {
        Object descriptions = cve.get("descriptions");
        if (!(descriptions instanceof List<?> list)) return null;
        for (Object d : list) {
            if (!(d instanceof Map<?, ?> m)) continue;
            if ("en".equalsIgnoreCase(String.valueOf(m.get("lang")))) {
                Object value = m.get("value");
                return value instanceof String s && !s.isBlank() ? s : null;
            }
        }
        return null;
    }

    private static Cvss extractCvss(Map<String, Object> cve) {
        Object metrics = cve.get("metrics");
        if (!(metrics instanceof Map<?, ?> m)) return Cvss.empty();
        Cvss cvss = fromMetricArray(m.get("cvssMetricV40"));
        if (cvss != null) return cvss;
        cvss = fromMetricArray(m.get("cvssMetricV31"));
        if (cvss != null) return cvss;
        cvss = fromMetricArray(m.get("cvssMetricV30"));
        return cvss != null ? cvss : Cvss.empty();
    }

    @SuppressWarnings("unchecked")
    private static Cvss fromMetricArray(Object metricArray) {
        if (!(metricArray instanceof List<?> list)) return null;
        for (Object entry : list) {
            Cvss result = fromMetric(entry);
            if (result != null) return result;
        }
        return null;
    }

    private static Cvss fromMetric(Object entry) {
        if (!(entry instanceof Map<?, ?> m)) return null;
        Object cvssData = m.get("cvssData");
        if (!(cvssData instanceof Map<?, ?> data)) return null;
        Object scoreObj = data.get("baseScore");
        Double score = (scoreObj instanceof Number n)
                ? com.salkcoding.oswl.service.cvss.CvssScore.validOrNull(n.doubleValue()) : null;
        String vector = data.get("vectorString") instanceof String s ? s : null;
        RiskLevel severity = cvssToRiskLevel(score);
        boolean hasSeverity = false;
        Object sevObj = data.get("baseSeverity");
        if (sevObj instanceof String s) {
            try {
                RiskLevel parsed = RiskLevel.valueOf(s.toUpperCase(java.util.Locale.ROOT));
                hasSeverity = true;
                if (severity == RiskLevel.NONE || parsed.ordinal() <= severity.ordinal()) {
                    severity = parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // keep score-derived severity
            }
        }
        if (vector != null && vector.isBlank()) vector = null;
        if (score == null && vector == null && !hasSeverity) return null;
        return new Cvss(severity, score, vector);
    }

    private static RiskLevel parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return RiskLevel.NONE;
        try {
            return RiskLevel.valueOf(severity.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RiskLevel.NONE;
        }
    }

    private static MatchConfidence parseConfidence(String confidence) {
        if (confidence == null || confidence.isBlank()) return null;
        try {
            return MatchConfidence.valueOf(confidence.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static RiskLevel cvssToRiskLevel(Double score) {
        if (score == null) return RiskLevel.NONE;
        if (score >= 9.0) return RiskLevel.CRITICAL;
        if (score >= 7.0) return RiskLevel.HIGH;
        if (score >= 4.0) return RiskLevel.MEDIUM;
        if (score > 0.0) return RiskLevel.LOW;
        return RiskLevel.NONE;
    }

    private record Cvss(RiskLevel severity, Double score, String vector) {
        static Cvss empty() {
            return new Cvss(RiskLevel.NONE, null, null);
        }
    }
}
