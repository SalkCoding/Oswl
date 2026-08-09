package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves a CocoaPods pod name + version to its source repository URL and license, via the
 * public {@code CocoaPods/Specs} trunk on GitHub — CocoaPods has no package registry API of its
 * own; the podspec files themselves are the only source of this metadata.
 *
 * <p>The repository URL is what OSV's {@code SwiftURL} ecosystem actually keys vulnerabilities
 * by (not the pod name — a CocoaPods pod and a Swift Package Manager package that share the same
 * GitHub repository are the same OSV entry). See ROADMAP A9.
 *
 * <p>Air-gapped mode: there is no offline bundle for the CocoaPods Specs index yet (unlike OSV/
 * NVD/GitHub Advisory data, this mapping isn't a vulnerability feed and would need its own
 * snapshot format), so this client simply returns empty in that mode — CocoaPods components then
 * show as not-analyzed rather than "no vulnerabilities" (same safe-fallback idiom as an unmapped
 * OSV ecosystem).
 */
@Slf4j
public class CocoaPodsSpecsClient {

    private static final String BASE_URL = "https://raw.githubusercontent.com/CocoaPods/Specs/master";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final boolean airgapped;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

    /** Live-HTTP client. Used directly by unit tests. */
    public CocoaPodsSpecsClient() {
        this(false);
    }

    public CocoaPodsSpecsClient(boolean airgapped) {
        this(airgapped, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public CocoaPodsSpecsClient(boolean airgapped, Duration connectTimeout, Duration readTimeout) {
        this.airgapped = airgapped;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .build();
        if (airgapped) {
            log.info("[CocoaPodsSpecs] Air-gapped mode — pod resolution skipped (no offline Specs index bundled), "
                    + "CocoaPods components will show as not-analyzed rather than a false 'no vulnerabilities'");
        }
    }

    /** Resolved podspec metadata needed to query OSV and to set the component's license. */
    public record PodSpecInfo(String repoUrl, String license) {}

    /**
     * Resolves a pod to its repository URL (host+path only, e.g. {@code github.com/Alamofire/
     * Alamofire} — no scheme, no {@code .git} suffix, matching OSV's {@code SwiftURL} convention)
     * and license. Empty when air-gapped, the pod/version doesn't exist in the Specs trunk, or the
     * pod has no {@code source.git} (e.g. it ships as a local/binary-only pod).
     */
    public Optional<PodSpecInfo> resolve(String podName, String version) {
        if (airgapped || podName == null || podName.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        String url = BASE_URL + "/Specs/" + md5Shard(podName) + "/" + podName + "/" + version + "/" + podName + ".podspec.json";
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            recordApiCall(OswlMetrics.OUTCOME_SUCCESS);
            if (body == null || body.isBlank()) {
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(body);
            String repoUrl = normalizeRepoUrl(root.path("source").path("git").asText(null));
            if (repoUrl == null) {
                return Optional.empty();
            }
            return Optional.of(new PodSpecInfo(repoUrl, extractLicense(root.path("license"))));
        } catch (HttpClientErrorException.NotFound e) {
            // Expected and common — pod or exact version not in the trunk (renamed, unpublished,
            // pinned to a fork, etc). Not a client failure, so it's not recorded as one.
            return Optional.empty();
        } catch (RestClientException e) {
            recordApiCall(OswlMetrics.OUTCOME_FAILURE);
            log.debug("[CocoaPodsSpecs] Resolution failed for {}@{}: {}", podName, version, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("[CocoaPodsSpecs] Unexpected error resolving {}@{}: {}", podName, version, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code https://github.com/Owner/Repo.git} → {@code github.com/Owner/Repo}, matching OSV's SwiftURL keys. Non-GitHub hosts return null — SwiftURL only covers GitHub-style repos. */
    private static String normalizeRepoUrl(String rawGitUrl) {
        if (rawGitUrl == null || rawGitUrl.isBlank()) {
            return null;
        }
        String url = rawGitUrl.strip();
        url = url.replaceFirst("^git@github\\.com:", "github.com/");
        url = url.replaceFirst("^\\w+://", "");
        url = url.replaceFirst("\\.git$", "");
        url = url.replaceFirst("/+$", "");
        if (!url.toLowerCase(Locale.ROOT).startsWith("github.com/")) {
            return null;
        }
        return url;
    }

    /** podspec {@code license} is either a plain string or {@code {"type": "...", ...}}. */
    private static String extractLicense(JsonNode licenseNode) {
        if (licenseNode == null || licenseNode.isMissingNode() || licenseNode.isNull()) {
            return null;
        }
        if (licenseNode.isTextual()) {
            String value = licenseNode.asText(null);
            return value != null && !value.isBlank() ? value.strip() : null;
        }
        String type = licenseNode.path("type").asText(null);
        return type != null && !type.isBlank() ? type.strip() : null;
    }

    private static String md5Shard(String podName) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(podName.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest);
            return hex.charAt(0) + "/" + hex.charAt(1) + "/" + hex.charAt(2);
        } catch (Exception e) {
            // MD5 is always available on any standard JVM — this branch is unreachable in practice.
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("cocoapods-specs", outcome);
        }
    }
}
