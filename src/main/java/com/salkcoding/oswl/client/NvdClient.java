package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * NVD(National Vulnerability Database) CVE API v2 선택적 클라이언트.
 *
 * ExternalApiSetting에 NVD API 키가 설정된 경우에만 호출된다.
 * LibraryCve 항목을 다음으로 수정(enrichment)한다:
 *   - CVSS 3.x 기본 점수  (deps.dev 값 덧어쓰기)
 *   - 기본 심각도 레이블
 *   - CWE ID
 *
 * 속도 제한:
 *   - API 키 없음: 5요청 / 30초
 *   - API 키 있음:    50요청 / 30초  → 여기서 700ms 지연 적용
 *
 * CVE- 접두사 ID만 NVD로 전송한다; GHSA 전용 어드바이저리는 건너뎇.
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

    // ── 공개 API ───────────────────────────────────────────────────────

    /**
     * 단일 CVE ID에 대한 NVD 메타데이터를 가져온다.
     * CVE를 찾지 못거나 NVD가 오류를 반환하면 null을 리턴한다.
     * 호출자는 속도 제한을 준수해야 한다 (지연을 두고 순차적으로 호출).
     */
    public NvdCveInfo fetchCve(String cveId, String apiKey) {
        if (cveId == null || !cveId.startsWith("CVE-")) {
            log.debug("[NvdClient] CVE가 아닌 ID 건너덗: {}", cveId);
            return null;
        }
        try {
            enforceRateLimit();

            log.debug("[NvdClient] fetchCve 요청 cveId={}", cveId);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri("?cveId={cveId}", cveId)
                    .header("apiKey", apiKey)
                    .retrieve()
                    .body(Map.class);

            log.debug("[NvdClient] fetchCve 응답 raw cveId={} response={}", cveId, response);

            NvdCveInfo result = parseResponse(response);
            log.debug("[NvdClient] fetchCve 파싱 cveId={} cvssScore={} severity={} cweId={}",
                    cveId,
                    result != null ? result.cvssScore() : null,
                    result != null ? result.severity() : null,
                    result != null ? result.cweId() : null);
            return result;
        } catch (RestClientException e) {
            log.warn("[NvdClient] {} 가져오기 실패: {}", cveId, e.getMessage());
            return null;
        }
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    private NvdCveInfo parseResponse(Map<String, Object> response) {
        if (response == null) return null;
        try {
            List<?> vulns = (List<?>) response.get("vulnerabilities");
            if (vulns == null || vulns.isEmpty()) return null;

            Map<?, ?> cveWrapper = (Map<?, ?>) vulns.get(0);
            Map<?, ?> cve = (Map<?, ?>) cveWrapper.get("cve");
            if (cve == null) return null;

            // CVSS v3.1 메트릭스
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
            log.warn("[NvdClient] 예상치 못한 응답 구조: {}", e.getMessage());
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
