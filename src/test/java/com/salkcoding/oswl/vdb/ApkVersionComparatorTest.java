package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class ApkVersionComparatorTest {
    @ParameterizedTest
    @CsvSource({"1.01,1.1,-1", "1.02,1.010,1", "1.0,1.00,-1", "01.2,1.2,0", "1.2,1.10,-1", "1.02,1.02,0"})
    void leadingZeroSegmentsRetainTheirOrdering(String left, String right, int sign) {
        assertThat(Integer.signum(ApkVersionComparator.compare(left, right))).isEqualTo(sign);
        assertThat(Integer.signum(ApkVersionComparator.compare(right, left))).isEqualTo(-sign);
    }

    @ParameterizedTest
    @CsvSource({"1.01,1.1,true", "1.02,1.010,false", "1.0,1.00,true", "1.2,1.10,true"})
    void leadingZerosReachRangeAndBulkFixDecisions(String installed, String fixed, boolean affected) throws Exception {
        String original = """
                {"id":"OSV-fixture","modified":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"Alpine:v3.18","name":"example"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"%s"}]}]}]}
                """.formatted(fixed);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var advisory = mapper.readTree(original);
        assertThat(OsvRangeEvaluator.evaluateAdvisory(advisory, "Alpine:v3.18", "example", installed))
                .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(advisory, "Alpine:v3.18", "example", installed).version())
                .isEqualTo(affected ? fixed : null);
        var findings = new java.util.LinkedHashMap<String, java.util.List<com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln>>();
        var unknown = new java.util.LinkedHashSet<String>();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry",
                original.getBytes(java.nio.charset.StandardCharsets.UTF_8), "ALPINE:V3.18",
                java.util.Map.of("example", java.util.Set.of(installed)), findings, unknown);
        assertThat(unknown).isEmpty();
        assertThat(findings).hasSize(affected ? 1 : 0);
        if (affected) assertThat(findings.values().iterator().next().getFirst().fixVersion()).isEqualTo(fixed);
    }
}
