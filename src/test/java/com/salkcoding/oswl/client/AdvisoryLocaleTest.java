package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@Isolated("Changes the process default locale")
class AdvisoryLocaleTest {
    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR", "az-AZ"})
    void packageRequestsUseProtocolCaseInEveryLocale(String tag) {
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(tag));
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new DepsDevClient();
            ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(requestTo("https://api.deps.dev/v3/systems/PYPI/packages/fixture/versions/1.0"))
                    .andRespond(withSuccess("{\"licenses\":[\"MIT\"],\"advisoryKeys\":[{\"id\":\"GHSA-fixture\"}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.deps.dev/v3/systems/PYPI/packages/fixture"))
                    .andRespond(withSuccess("{\"versions\":[{\"versionKey\":{\"version\":\"2.0\"},\"isDefault\":true}]}", MediaType.APPLICATION_JSON));
            var result = client.getVersionsBatch(List.of(new DepsDevClient.ComponentKey("pypi", "fixture", "1.0"))).getFirst();
            assertThat(result).isNotNull();
            assertThat(result.resolved()).isTrue();
            assertThat(result.advisoryKeys()).containsExactly("GHSA-fixture");
            assertThat(result.latestVersion()).isEqualTo("2.0");
            server.verify();
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "tr-TR", "az-AZ"})
    void nvdLabelsDoNotDependOnHostLocale(String tag) {
        Locale previous = Locale.getDefault();
        Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale previousFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.forLanguageTag(tag));
            var client = new NvdClient();
            List<NvdClient.NvdCve> results = ReflectionTestUtils.invokeMethod(client, "parseBody",
                    Map.of("totalResults", 1, "startIndex", 0, "resultsPerPage", 1, "vulnerabilities", List.of(Map.of("cve", Map.of("id", "CVE-2026-0001", "metrics",
                            Map.of("cvssMetricV31", List.of(Map.of("cvssData", Map.of("baseSeverity", "critical")))))))), null);
            assertThat(results.getFirst().severity()).isEqualTo(RiskLevel.CRITICAL);
            RiskLevel offlineSeverity = ReflectionTestUtils.invokeMethod(NvdClient.class, "parseSeverity", "critical");
            MatchConfidence confidence = ReflectionTestUtils.invokeMethod(NvdClient.class, "parseConfidence", "high");
            assertThat(offlineSeverity).isEqualTo(RiskLevel.CRITICAL);
            assertThat(confidence).isEqualTo(MatchConfidence.HIGH);
        } finally {
            Locale.setDefault(previous);
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            Locale.setDefault(Locale.Category.FORMAT, previousFormat);
        }
    }
}
