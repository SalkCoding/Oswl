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
 * OSV.dev REST API 클라이언트.
 *
 * querybatch 엔드포인트(POST /v1/querybatch)만 사용한다 —
 * /v1/query에 대한 단일 항목 반복 호출은 의도적으로 피한다.
 * 하나의 배치 호출에 위 1,000개 쿼리를 실어 보낼 수 있으며 속도 제한 없음.
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

    // ── DTO ────────────────────────────────────────────────────────────

    /** 단일 OSV 취약점 항목에서 추출된 취약점 세부 정보 */
    public record OsvVuln(
            String osvId,
            String cveId,
            String summary,
            String fixVersion) {}

    /** 하나의 쿼리에 대한 결과 세트 (입력 배치 인덱스와 정렬) */
    public record OsvResult(List<OsvVuln> vulns) {}

    // ── 공개 API ───────────────────────────────────────────────────────

    /**
     * 컴포넌트를 최대 1,000개 단위로 배치 전송하고 입력 목록과 정렬된 결과를 반환한다.
     * 빈 OsvResult(vulns = [])는 해당 컴포넌트에 취약점이 없다는 의미이다.
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

    // ── 내부 ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<OsvResult> doQueryBatch(List<OsvQuery> queries) {
        try {
            // Map.of()는 null 값을 거부 — null 필드를 가진 쿼리를 사전 필터링하고
            // 결과 목록 재정렬을 위해 원본 인덱스를 추적.
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

            log.debug("[OsvClient] querybatch 요청 size={} valid={} queries={}",
                    queries.size(), validIndices.size(), queries);

            Map<String, Object> response = restClient.post()
                    .uri("/v1/querybatch")
                    .header("Content-Type", "application/json")
                    .body(Map.of("queries", requestBody))
                    .retrieve()
                    .body(Map.class);

            log.debug("[OsvClient] querybatch 응답 raw={}", response);

            if (response == null || !response.containsKey("results")) {
                log.debug("[OsvClient] querybatch 응답 비어있거나 'results' 키 없음");
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
            log.debug("[OsvClient] querybatch 파싱 results count={} totalVulns={}",
                    parsed.size(),
                    parsed.stream().mapToInt(r -> r.vulns().size()).sum());

            // 정에 맞게 queries.size()로 확장, 버전이 null인 경우 빈 결과 삽입
            OsvResult[] finalResults = new OsvResult[queries.size()];
            java.util.Arrays.fill(finalResults, new OsvResult(List.of()));
            for (int i = 0; i < validIndices.size() && i < parsed.size(); i++) {
                finalResults[validIndices.get(i)] = parsed.get(i);
            }
            for (int i = 0; i < finalResults.length; i++) {
                OsvQuery q = queries.get(i);
                log.debug("[OsvClient] querybatch 결과[{}] {}:{} vulns={}", i, q.name(), q.version(), finalResults[i].vulns());
            }
            return java.util.Arrays.asList(finalResults);
        } catch (RestClientException e) {
            log.error("[OsvClient] querybatch 실패: {}", e.getMessage());
            return Collections.nCopies(queries.size(), new OsvResult(List.of()));
        }
    }

    private OsvVuln parseVuln(Map<String, Object> vuln) {
        String osvId  = (String) vuln.get("id");
        String summary = (String) vuln.get("summary");

        // aliases에서 CVE ID 추출
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

        // affected[].ranges[].events[fixed]에서 수정 버전 추출
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

    // ── 값 타입 ───────────────────────────────────────────────────────

    public record OsvQuery(String ecosystem, String name, String version) {}
}
