package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Optional client for the NVD (National Vulnerability Database) CVE API v2.
 *
 * It is called only when an NVD API key is configured in ExternalApiSetting.
 * It enriches LibraryCve entries with the following:
 *   - CVSS 3.x base score (overriding the deps.dev value)
 *   - Base severity label
 *   - CWE ID
 *
 * Rate limits:
 *   - Without API key: 5 requests / 30 seconds
 *   - With API key:    50 requests / 30 seconds  → a 700ms delay is applied here
 *
 * Only CVE-prefixed IDs are sent to NVD; GHSA-only advisories are skipped.
 */
@Slf4j
@Component
public class NvdClient {

    private static final String BASE_URL = "https://services.nvd.nist.gov/rest/json/cves/2.0";
    private static final long DELAY_MILLIS = 700L;

    private final RestClient restClient;

    public NvdClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    // ── DTO ────────────────────────────────────────────────────────────

    public record NvdCveInfo(Double cvssScore, String severity, String cweId) {}

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Fetches NVD metadata for a single CVE ID.
     * Returns null if the CVE is not found or NVD returns an error.
     * Callers must respect the rate limit by invoking sequentially with delay.
     */
    public NvdCveInfo fetchCve(String cveId, String apiKey) {
        if (cveId == null || !cveId.startsWith("CVE-")) {
            log.debug("[NvdClient] Skipping non-CVE ID: {}", cveId);
            return null;
        }
        try {
            enforceRateLimit();

            log.debug("[NvdClient] fetchCve request cveId={}", cveId);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("?cveId={cveId}", cveId)
                    .header("apiKey", apiKey)
                    .retrieve()
                    .body(Map.class);

            log.debug("[NvdClient] fetchCve response raw cveId={} response={}", cveId, response);

            NvdCveInfo result = parseResponse(response);
            log.debug("[NvdClient] fetchCve parsed cveId={} cvssScore={} severity={} cweId={}",
                    cveId,
                    result != null ? result.cvssScore() : null,
                    result != null ? result.severity() : null,
                    result != null ? result.cweId() : null);
            return result;
        } catch (RestClientException e) {
            log.warn("[NvdClient] Failed to fetch {}: {}", cveId, e.getMessage());
            return null;
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private NvdCveInfo parseResponse(Map<String, Object> response) {
        if (response == null) return null;
        try {
            List<?> vulns = (List<?>) response.get("vulnerabilities");
            if (vulns == null || vulns.isEmpty()) return null;

            Map<?, ?> cveWrapper = (Map<?, ?>) vulns.get(0);
            Map<?, ?> cve = (Map<?, ?>) cveWrapper.get("cve");
            if (cve == null) return null;

            // CVSS v3.1 metrics
            Map<?, ?> metrics = (Map<?, ?>) cve.get("metrics");
            Double cvssScore = null;
            String severity  = null;
            if (metrics != null) {
                List<?> cvssV31 = (List<?>) metrics.get("cvssMetricV31");
                if (cvssV31 != null && !cvssV31.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) cvssV31.get(0);
                    Map<?, ?> data  = (Map<?, ?>) first.get("cvssData");
                    if (data != null) {
                        Object score = data.get("baseScore");
                        if (score instanceof Number n) cvssScore = n.doubleValue();
                        Object sev = data.get("baseSeverity");
                        if (sev instanceof String s) severity = s;
                    }
                }
            }

            // CWE ID
            String cweId = null;
            List<?> weaknesses = (List<?>) cve.get("weaknesses");
            if (weaknesses != null && !weaknesses.isEmpty()) {
                Map<?, ?> w = (Map<?, ?>) weaknesses.get(0);
                List<?> descs = (List<?>) w.get("description");
                if (descs != null && !descs.isEmpty()) {
                    Map<?, ?> d = (Map<?, ?>) descs.get(0);
                    Object v = d.get("value");
                    if (v instanceof String s) cweId = s;
                }
            }

            return new NvdCveInfo(cvssScore, severity, cweId);
        } catch (ClassCastException e) {
            log.warn("[NvdClient] Unexpected response structure: {}", e.getMessage());
            return null;
        }
    }

    private void enforceRateLimit() {
        try {
            Thread.sleep(DELAY_MILLIS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
