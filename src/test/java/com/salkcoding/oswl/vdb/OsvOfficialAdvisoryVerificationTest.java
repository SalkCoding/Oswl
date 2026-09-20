package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in official-source check; raw third-party advisory text is not stored as a fixture. */
@EnabledIfEnvironmentVariable(named = "OSWL_VERIFY_LIVE_OSV", matches = "true")
class OsvOfficialAdvisoryVerificationTest {
    @Test void formDataBranchFixesMatchOfficialRecordsAndRetainedBulkEvidence() throws Exception {
        String id = "GHSA-fjxv-7rqg-78g4";
        String upstream = "https://raw.githubusercontent.com/github/advisory-database/main/advisories/github-reviewed/2025/07/"
                + id + "/" + id + ".json";
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            JsonNode osv = fetch(http, "https://api.osv.dev/v1/vulns/" + id);
            JsonNode github = fetch(http, upstream);
            for (var original : List.of(osv, github)) {
                assertThat(original.path("id").asText()).isEqualTo(id);
                for (var pair : Map.of("2.5.3", "2.5.4", "3.0.3", "3.0.4", "4.0.3", "4.0.4").entrySet()) {
                    assertThat(OsvRangeEvaluator.evaluateAdvisory(original, "npm", "form-data", pair.getKey()))
                            .isEqualTo(OsvRangeEvaluator.Result.AFFECTED);
                    assertThat(OsvRangeEvaluator.evaluateAdvisory(original, "npm", "form-data", pair.getValue()))
                            .isEqualTo(OsvRangeEvaluator.Result.NOT_AFFECTED);
                    assertThat(OsvFixVersionSelector.select(original, "npm", "form-data", pair.getKey()).version())
                            .isEqualTo(pair.getValue());
                }
            }
            assertThat(OsvOriginalAttribution.githubSource(osv)).isEqualTo(upstream.replace(
                    "https://raw.githubusercontent.com/github/advisory-database/main/",
                    "https://github.com/github/advisory-database/blob/main/"));
            Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
            Set<String> unknown = new LinkedHashSet<>();
            ReflectionTestUtils.invokeMethod(new OsvBulkSource(new ObjectMapper()), "processVulnEntry",
                    osv.toString().getBytes(StandardCharsets.UTF_8), "NPM",
                    Map.of("form-data", Set.of("2.5.3", "2.5.4", "3.0.3", "3.0.4", "4.0.3", "4.0.4")), findings, unknown);
            assertThat(unknown).isEmpty();
            assertThat(findings).containsOnlyKeys("NPM|form-data|2.5.3", "NPM|form-data|3.0.3", "NPM|form-data|4.0.3");
            for (var pair : Map.of("2.5.3", "2.5.4", "3.0.3", "3.0.4", "4.0.3", "4.0.4").entrySet()) {
                assertThat(findings.get("NPM|form-data|" + pair.getKey())).singleElement().satisfies(v -> {
                    assertThat(v.fixVersion()).isEqualTo(pair.getValue());
                    assertThat(v.osvAdvisory()).isEqualTo(osv);
                });
            }
        }
    }

    @Test void actualQueryBatchRespectsTheOfficialFixedBoundaries() throws Exception {
        var pairs = Map.of("2.5.3", "2.5.4", "3.0.3", "3.0.4", "4.0.3", "4.0.4");
        var versions = pairs.entrySet().stream().flatMap(pair -> java.util.stream.Stream.of(pair.getKey(), pair.getValue())).toList();
        var queries = versions.stream().map(version -> new com.salkcoding.oswl.client.OsvClient.OsvQuery(
                "npm", "form-data", version)).toList();
        var results = new com.salkcoding.oswl.client.OsvClient().queryBatch(queries);
        assertThat(results).hasSize(queries.size());
        Map<String, JsonNode> originals = new LinkedHashMap<>();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()) {
            for (var result : results) {
                for (String id : result.advisoryRevisions().keySet()) {
                    if (!originals.containsKey(id)) originals.put(id, fetch(http, "https://api.osv.dev/v1/vulns/" + id));
                    var original = originals.get(id);
                    assertThat(OsvOriginalAttribution.githubSource(original)).as("declared upstream source of %s", id).isNotNull();
                    assertThat(original.path("modified").asText()).isEqualTo(result.advisoryRevisions().get(id));
                    assertThat(OsvOriginalDigest.of(original)).isEqualTo(result.advisoryDigests().get(id));
                }
            }
        }
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        for (var original : originals.values()) {
            ReflectionTestUtils.invokeMethod(new OsvBulkSource(new ObjectMapper()), "processVulnEntry",
                    original.toString().getBytes(StandardCharsets.UTF_8), "NPM",
                    Map.of("form-data", Set.copyOf(versions)), findings, unknown);
        }

        for (int i = 0; i < queries.size(); i++) {
            String version = versions.get(i);
            var result = results.get(i);
            assertThat(result.resolved()).as("actual OSV lookup for form-data %s", version).isTrue();
            var matched = result.vulns().stream().filter(v -> v.osvId().equals("GHSA-fjxv-7rqg-78g4")).toList();
            if (pairs.containsKey(version)) {
                assertThat(matched).singleElement().satisfies(v -> assertThat(v.fixVersion()).isEqualTo(pairs.get(version)));
                assertThat(result.advisoryRevisions()).containsKey("GHSA-fjxv-7rqg-78g4");
                assertThat(result.advisoryDigests()).containsKey("GHSA-fjxv-7rqg-78g4");
            } else assertThat(matched).isEmpty();
            String key = "NPM|form-data|" + version;
            // Coverage here is the completed live lookup above, not an entire ecosystem dump.
            findings.putIfAbsent(key, List.of());
            var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
            org.mockito.Mockito.when(snapshots.readOsvSnapshot(org.mockito.ArgumentMatchers.anyCollection()))
                    .thenReturn(new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.VulnerabilitySnapshotView(
                            findings, unknown, false, result.validUntil()));
            var offline = new com.salkcoding.oswl.client.OsvClient(snapshots, true).queryBatch(List.of(queries.get(i))).getFirst();
            assertThat(offline.resolved()).isEqualTo(result.resolved());
            assertThat(offline.vulns()).containsExactlyInAnyOrderElementsOf(result.vulns());
            assertThat(offline.commonFix()).isEqualTo(result.commonFix());
            assertThat(offline.advisoryRevisions()).isEqualTo(result.advisoryRevisions());
            assertThat(offline.advisoryDigests()).isEqualTo(result.advisoryDigests());
            assertThat(offline.validUntil()).isEqualTo(result.validUntil());
            System.out.println("Live/bulk parity: form-data " + version + " commonFix=" + result.commonFix());
            System.out.println("Actual OSV boundary check: form-data " + version + " findings=" + result.vulns().size()
                    + " targetAdvisoryPresent=" + !matched.isEmpty() + " revisions=" + result.advisoryRevisions());
        }
    }

    private JsonNode fetch(HttpClient http, String url) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(url).isEqualTo(200);
        var original = new ObjectMapper().reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.body());
        System.out.println("Official advisory verification: " + url + " modified=" + original.path("modified").asText()
                + " digest=" + OsvOriginalDigest.of(original));
        return original;
    }
}
