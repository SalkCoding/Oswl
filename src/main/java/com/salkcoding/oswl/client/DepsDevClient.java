package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * deps.dev REST API v3 클라이언트.
 *
 * 사용하는 엔드포인트:
 *   GET /v3/systems/{system}/packages/{packageName}/versions/{version}  → 라이선스 + advisoryKeys
 *   GET /v3/advisories/{advisoryId}                                       → CVE 세부정보 + CVSS 점수
 *
 * deps.dev는 배치 엔드포인트가 없으므로 GetVersion 호출은
 * 가상 쓰레드 실행기를 통해 병렬 처리된다 (10개 동시 요청 제한).
 */
@Slf4j
@Component
public class DepsDevClient {

    private static final String BASE_URL = "https://api.deps.dev";

    private final RestClient restClient;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DepsDevClient() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/json")
                .build();
    }

    // ── DTO ────────────────────────────────────────────────────────────

    /**
     * @param isDefault     패키지의 기본(latest stable) 버전이면 true
     * @param deprecated    버전이 더이상 사용되지 않을 경우 비고 문자열(비어있지 않음); 그렇지 않으면 null
     * @param latestVersion 이미 최신이 아닐 경우 최신 stable 버전 문자열; 최신이면 null
     */
    public record VersionInfo(List<String> licenses, List<String> advisoryKeys,
                              boolean isDefault, String deprecated, String latestVersion) {}

    public record AdvisoryInfo(
            String ghsaId,
            String title,
            List<String> aliases,
            Double cvss3Score,
            String cvss3Vector) {}

    // ── 공개 API ───────────────────────────────────────────────────────

    /**
     * 모든 컴포넌트에 대해 GetVersion을 병렬 호출한다 (최대 10개 동시).
     * 입력 목록과 정렬된 목록을 반환한다; null은 패키지 조회 실패를 의미한다.
     */
    public List<VersionInfo> getVersionsBatch(List<ComponentKey> components) {
        List<CompletableFuture<VersionInfo>> futures = components.stream()
                .map(key -> CompletableFuture.supplyAsync(() -> getVersion(key), executor))
                .toList();

        List<VersionInfo> results = new ArrayList<>(components.size());
        for (CompletableFuture<VersionInfo> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                log.warn("[DepsDevClient] getVersion 실패: {}", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    /**
     * 모든 GHSA ID에 대해 GetAdvisory를 병렬 호출한다.
     * 입력 목록과 정렬된 목록을 반환한다; null은 조회 실패를 의미한다.
     */
    public List<AdvisoryInfo> getAdvisoriesBatch(List<String> ghsaIds) {
        List<CompletableFuture<AdvisoryInfo>> futures = ghsaIds.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> getAdvisory(id), executor))
                .toList();

        List<AdvisoryInfo> results = new ArrayList<>(ghsaIds.size());
        for (CompletableFuture<AdvisoryInfo> f : futures) {
            try {
                results.add(f.join());
            } catch (Exception e) {
                log.warn("[DepsDevClient] getAdvisory 실패: {}", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    private VersionInfo getVersion(ComponentKey key) {
        try {
            if (key.version() == null || key.version().isBlank()) {
                log.debug("[DepsDevClient] GetVersion 건너땀 {}:{} — 버전 null/빈 문자열", key.name(), key.version());
                return new VersionInfo(List.of(), List.of(), false, null, null);
            }
            String encodedName = encodePackageName(key.ecosystem(), key.name());
            String encodedVersion = URLEncoder.encode(key.version(), StandardCharsets.UTF_8).replace("+", "%20");

            log.debug("[DepsDevClient] GetVersion 요청 ecosystem={} name={} version={}",
                    key.ecosystem(), key.name(), key.version());

            // 이미 percent-encode된 문자열을 URI 템플릿 변수로 넘기면 %25XX로 이중 인코딩됨.
            // build(true) → "already encoded" 플래그로 re-encoding 방지.
            String path = String.format("/v3/systems/%s/packages/%s/versions/%s",
                    key.ecosystem().toUpperCase(), encodedName, encodedVersion);
            java.net.URI uri = UriComponentsBuilder.fromUriString(BASE_URL + path).build(true).toUri();

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                log.debug("[DepsDevClient] GetVersion 응답 body null {}:{}", key.name(), key.version());
                return new VersionInfo(List.of(), List.of(), false, null, null);
            }

            List<String> licenses = extractStrings(body, "licenses");

            List<String> advisoryKeys = new ArrayList<>();
            Object akRaw = body.get("advisoryKeys");
            if (akRaw instanceof List<?> akList) {
                for (Object ak : akList) {
                    if (ak instanceof Map<?, ?> akMap) {
                        Object id = akMap.get("id");
                        if (id instanceof String s) advisoryKeys.add(s);
                    }
                }
            }

            boolean isDefault = Boolean.TRUE.equals(body.get("isDefault"));
            Object depRaw = body.get("deprecated");
            String deprecated = (depRaw instanceof String s && !s.isBlank()) ? s : null;

            // relatedVersions에서 최신 stable 버전 추출 (relationshipType == "DEFAULT")
            String latestVersion = null;
            if (!isDefault) {
                Object rvRaw = body.get("relatedVersions");
                if (rvRaw instanceof List<?> rvList) {
                    for (Object rv : rvList) {
                        if (rv instanceof Map<?, ?> rvMap
                                && "DEFAULT".equals(rvMap.get("relationshipType"))) {
                            Object vk = rvMap.get("versionKey");
                            if (vk instanceof Map<?, ?> vkMap) {
                                Object v = vkMap.get("version");
                                if (v instanceof String vs && !vs.isBlank()) {
                                    latestVersion = vs;
                                }
                            }
                            break;
                        }
                    }
                }
            }

            log.debug("[DepsDevClient] GetVersion 응답 {}:{} licenses={} advisoryKeys={} isDefault={} deprecated={} latestVersion={}",
                    key.name(), key.version(), licenses, advisoryKeys, isDefault, deprecated, latestVersion);
            return new VersionInfo(licenses, advisoryKeys, isDefault, deprecated, latestVersion);
        } catch (RestClientException e) {
            log.debug("[DepsDevClient] GetVersion 404/오류 {}:{} - {}", key.name(), key.version(), e.getMessage());
            return new VersionInfo(List.of(), List.of(), false, null, null);
        }
    }

    private AdvisoryInfo getAdvisory(String ghsaId) {
        try {
            log.debug("[DepsDevClient] GetAdvisory 요청 ghsaId={}", ghsaId);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri("/v3/advisories/{id}", ghsaId)
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                log.debug("[DepsDevClient] GetAdvisory 응답 body null ghsaId={}", ghsaId);
                return null;
            }

            List<String> aliases = extractStrings(body, "aliases");
            Double score = null;
            Object s = body.get("cvss3Score");
            if (s instanceof Number n) score = n.doubleValue();

            AdvisoryInfo result = new AdvisoryInfo(
                    ghsaId,
                    (String) body.get("title"),
                    aliases,
                    score,
                    (String) body.get("cvss3Vector"));
            log.debug("[DepsDevClient] GetAdvisory 응답 ghsaId={} title={} cvssScore={} aliases={}",
                    ghsaId, result.title(), result.cvss3Score(), result.aliases());
            return result;
        } catch (RestClientException e) {
            log.debug("[DepsDevClient] GetAdvisory {} 오류 - {}", ghsaId, e.getMessage());
            return null;
        }
    }

    /**
     * 패키지 이름을 단일 URL 경로 세그먼트로 인코딩한다.
     * Maven: groupId:artifactId → groupId%3AartifactId
     * npm scope: @org/pkg → %40org%2Fpkg
     * Go: github.com/x/y → github.com%2Fx%2Fy
     */
    private String encodePackageName(String ecosystem, String name) {
        return switch (ecosystem.toUpperCase()) {
            case "MAVEN" -> name.replace(":", "%3A");
            case "NPM"   -> name.replace("@", "%40").replace("/", "%2F");
            case "GO"    -> name.replace("/", "%2F");
            default      -> URLEncoder.encode(name, StandardCharsets.UTF_8)
                                     .replace("+", "%20");
        };
    }

    private List<String> extractStrings(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return Collections.emptyList();
    }

    // ── 값 타입 ───────────────────────────────────────────────────────

    public record ComponentKey(String ecosystem, String name, String version) {}
}
