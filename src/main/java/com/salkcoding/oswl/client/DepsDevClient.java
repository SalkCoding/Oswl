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
 * deps.dev REST API v3 client.
 *
 * Endpoints used:
 *   GET /v3/systems/{system}/packages/{packageName}/versions/{version}  → licenses + advisoryKeys
 *   GET /v3/advisories/{advisoryId}                                       → CVE details + CVSS score
 *
 * Since deps.dev has no batch endpoint, GetVersion calls are processed in parallel
 * through a virtual-thread executor (limited to 10 concurrent requests).
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
     * @param isDefault     true if this is the package's default (latest stable) version
     * @param deprecated    non-empty note string if the version is no longer used; otherwise null
     * @param latestVersion latest stable version string if already outdated; null if up to date
     */
    public record VersionInfo(List<String> licenses, List<String> advisoryKeys,
                              boolean isDefault, String deprecated, String latestVersion) {}

    public record AdvisoryInfo(
            String ghsaId,
            String title,
            List<String> aliases,
            Double cvss3Score,
            String cvss3Vector) {}

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Calls GetVersion in parallel for all components (up to 10 concurrent calls).
     * Returns a list aligned with the input list; null means the package lookup failed.
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
                log.warn("[DepsDevClient] getVersion failed: {}", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    /**
     * Calls GetAdvisory in parallel for all GHSA IDs.
     * Returns a list aligned with the input list; null means the lookup failed.
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
                log.warn("[DepsDevClient] getAdvisory failed: {}", e.getMessage());
                results.add(null);
            }
        }
        return results;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private VersionInfo getVersion(ComponentKey key) {
        try {
            if (key.version() == null || key.version().isBlank()) {
                log.debug("[DepsDevClient] Skipping GetVersion {}:{} — version is null/blank", key.name(), key.version());
                return new VersionInfo(List.of(), List.of(), false, null, null);
            }
            String encodedName = encodePackageName(key.ecosystem(), key.name());
            String encodedVersion = URLEncoder.encode(key.version(), StandardCharsets.UTF_8).replace("+", "%20");

            log.debug("[DepsDevClient] GetVersion request ecosystem={} name={} version={}",
                    key.ecosystem(), key.name(), key.version());

            // Passing an already percent-encoded string as a URI template variable causes double-encoding to %25XX.
            // build(true) prevents re-encoding by marking it as "already encoded".
            String path = String.format("/v3/systems/%s/packages/%s/versions/%s",
                    key.ecosystem().toUpperCase(), encodedName, encodedVersion);
            java.net.URI uri = UriComponentsBuilder.fromUriString(BASE_URL + path).build(true).toUri();

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                log.debug("[DepsDevClient] GetVersion response body null {}:{}", key.name(), key.version());
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

            // Extract the latest stable version from relatedVersions (relationshipType == "DEFAULT")
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

            log.debug("[DepsDevClient] GetVersion response {}:{} licenses={} advisoryKeys={} isDefault={} deprecated={} latestVersion={}",
                    key.name(), key.version(), licenses, advisoryKeys, isDefault, deprecated, latestVersion);
            return new VersionInfo(licenses, advisoryKeys, isDefault, deprecated, latestVersion);
        } catch (RestClientException e) {
            log.debug("[DepsDevClient] GetVersion 404/error {}:{} - {}", key.name(), key.version(), e.getMessage());
            return new VersionInfo(List.of(), List.of(), false, null, null);
        }
    }

    private AdvisoryInfo getAdvisory(String ghsaId) {
        try {
            log.debug("[DepsDevClient] GetAdvisory request ghsaId={}", ghsaId);

            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri("/v3/advisories/{id}", ghsaId)
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                log.debug("[DepsDevClient] GetAdvisory response body null ghsaId={}", ghsaId);
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
            log.debug("[DepsDevClient] GetAdvisory response ghsaId={} title={} cvssScore={} aliases={}",
                    ghsaId, result.title(), result.cvss3Score(), result.aliases());
            return result;
        } catch (RestClientException e) {
            log.debug("[DepsDevClient] GetAdvisory {} error - {}", ghsaId, e.getMessage());
            return null;
        }
    }

    /**
     * Encodes a package name as a single URL path segment.
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

    // ── Value types ───────────────────────────────────────────────────────

    public record ComponentKey(String ecosystem, String name, String version) {}
}
