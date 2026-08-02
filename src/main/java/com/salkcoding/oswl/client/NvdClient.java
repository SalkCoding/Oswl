package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.AirgappedSnapshotService;
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

    /** Live-HTTP client with no API key. Used directly by unit tests. */
    public NvdClient() {
        this(null, false, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public NvdClient(AirgappedSnapshotService snapshotService, boolean airgapped, String apiKey,
                     Duration connectTimeout, Duration readTimeout) {
        this.snapshotService = snapshotService;
        this.airgapped = airgapped && snapshotService != null;
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
                         Double cvssScore, String cvss3Vector, MatchConfidence matchConfidence) {}

    /**
     * Queries live NVD by exact CPE name. Returns an empty list when the CPE is blank,
     * the client is air-gapped, or the request fails.
     */
    public List<NvdCve> findByCpeName(String cpeName, MatchConfidence confidence) {
        if (airgapped || cpeName == null || cpeName.isBlank()) {
            return List.of();
        }
        String url = BASE_URL + "?cpeName=" + URLEncoder.encode(cpeName.strip(), StandardCharsets.UTF_8);
        try {
            return parseBody(doRequest(url), confidence);
        } catch (Exception e) {
            log.warn("[NVD] CPE lookup failed for {}: {}", cpeName, e.getMessage());
            return List.of();
        }
    }

    /**
     * Offline path: looks up NVD-derived CVEs by component key.
     * Returns a map keyed by the input component keys; absent keys mean "no known vulnerabilities".
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
                result.put(key, List.of());
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
                        .uri(url)
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
                return body != null ? body : Map.of();
            } catch (RestClientException e) {
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

    @SuppressWarnings("unchecked")
    private List<NvdCve> parseBody(Map<String, Object> body, MatchConfidence confidence) {
        if (body == null) return List.of();
        Object vulns = body.get("vulnerabilities");
        if (!(vulns instanceof List<?> list)) return List.of();
        List<NvdCve> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> v)) continue;
            Map<String, Object> cve = (Map<String, Object>) v.get("cve");
            if (cve == null) continue;
            String id = (String) cve.get("id");
            if (id == null) continue;
            Cvss cvss = extractCvss(cve);
            result.add(new NvdCve(id, extractDescription(cve), cvss.severity, cvss.score, cvss.vector, confidence));
        }
        return result;
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
        Cvss cvss = fromMetricArray(m.get("cvssMetricV31"));
        if (cvss != null) return cvss;
        cvss = fromMetricArray(m.get("cvssMetricV30"));
        return cvss != null ? cvss : Cvss.empty();
    }

    @SuppressWarnings("unchecked")
    private static Cvss fromMetricArray(Object metricArray) {
        if (!(metricArray instanceof List<?> list) || list.isEmpty()) return null;
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> m)) return null;
        Object cvssData = m.get("cvssData");
        if (!(cvssData instanceof Map<?, ?> data)) return null;
        Object scoreObj = data.get("baseScore");
        Double score = (scoreObj instanceof Number n) ? n.doubleValue() : null;
        String vector = data.get("vectorString") instanceof String s ? s : null;
        RiskLevel severity = cvssToRiskLevel(score);
        Object sevObj = data.get("baseSeverity");
        if (sevObj instanceof String s) {
            try {
                RiskLevel parsed = RiskLevel.valueOf(s.toUpperCase());
                if (severity == RiskLevel.NONE || parsed.ordinal() <= severity.ordinal()) {
                    severity = parsed;
                }
            } catch (IllegalArgumentException ignored) {
                // keep score-derived severity
            }
        }
        return new Cvss(severity, score, vector);
    }

    private static RiskLevel parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return RiskLevel.NONE;
        try {
            return RiskLevel.valueOf(severity.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RiskLevel.NONE;
        }
    }

    private static MatchConfidence parseConfidence(String confidence) {
        if (confidence == null || confidence.isBlank()) return null;
        try {
            return MatchConfidence.valueOf(confidence.strip().toUpperCase());
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
