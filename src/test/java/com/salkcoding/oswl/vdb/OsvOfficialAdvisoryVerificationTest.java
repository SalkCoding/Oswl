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
