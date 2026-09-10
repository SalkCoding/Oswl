package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NvdMetricFallbackTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"score", "vector", "label"})
    void aUsablePartialObservationIsNotMixedWithTheNextOne(String kind) {
        Map<String, Object> first = switch (kind) {
            case "score" -> Map.of("baseScore", 7.5);
            case "vector" -> Map.of("vectorString", "first-vector");
            default -> Map.of("baseSeverity", "HIGH");
        };
        var metrics = Map.of("cvssMetricV31", List.of(Map.of("cvssData", first),
                Map.of("cvssData", Map.of("baseScore", 9.8, "vectorString", "next-vector"))));
        List<NvdClient.NvdCve> results = ReflectionTestUtils.invokeMethod(new NvdClient(), "parseBody",
                Map.of("vulnerabilities", List.of(Map.of("cve", Map.of("id", "CVE-2026-0001", "metrics", metrics)))), null);
        assertThat(results.getFirst().cvssScore()).isEqualTo(kind.equals("score") ? Double.valueOf(7.5) : null);
        assertThat(results.getFirst().cvss3Vector()).isEqualTo(kind.equals("vector") ? "first-vector" : null);
        assertThat(results.getFirst().severity()).isEqualTo(kind.equals("vector") ? RiskLevel.NONE : RiskLevel.HIGH);
    }

    @ParameterizedTest
    @CsvSource({"scalar,false", "missing,false", "empty,false", "invalid,false", "blank,false",
            "scalar,true", "missing,true", "empty,true", "invalid,true", "blank,true"})
    void unusableMetricDoesNotHideAvailableEvaluation(String kind, boolean lowerVersion) {
        Object unusable = switch (kind) {
            case "scalar" -> 42;
            case "missing" -> Map.of();
            case "invalid" -> Map.of("cvssData", Map.of("baseScore", 11));
            case "blank" -> Map.of("cvssData", Map.of("vectorString", " "));
            default -> Map.of("cvssData", Map.of());
        };
        var usable = Map.of("cvssData", Map.of("baseScore", 7.5, "vectorString", "available-vector"));
        var metrics = lowerVersion
                ? Map.of("cvssMetricV40", List.of(unusable), "cvssMetricV31", List.of(usable))
                : Map.of("cvssMetricV31", List.of(unusable, usable));
        List<NvdClient.NvdCve> results = ReflectionTestUtils.invokeMethod(new NvdClient(), "parseBody",
                Map.of("vulnerabilities", List.of(Map.of("cve", Map.of("id", "CVE-2026-0001", "metrics", metrics)))), null);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().cvssScore()).isEqualTo(7.5);
        assertThat(results.getFirst().cvss3Vector()).isEqualTo("available-vector");
        assertThat(results.getFirst().severity()).isEqualTo(RiskLevel.HIGH);
    }
}
