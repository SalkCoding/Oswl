package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;

class ApkVersionComparatorTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"v1.0", "V1.0", "1.0p1", "1.0rc1", "1.0alpha", " 1.0", "1.0 "})
    void unsupportedSpellingsAreNotRewrittenToAnotherVersion(String value) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApkVersionComparator.compare(value, "2.0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OsvQueryIdentity.isConcrete("Alpine:v3.18", "example", value)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"1.01,1.1,-1", "1.02,1.010,1", "1.0,1.00,-1", "01.2,1.2,0", "1.2,1.10,-1", "1.02,1.02,0",
            "1,1.0,-1", "1.0,1.0.0,-1", "1.0,1.0-r0,-1", "1.0_p,1.0_p0,-1",
            "1.0_rc,1.0_rc0,-1", "1.0-r0,1.0-r00,0", "1.0_p0,1.0_p00,0", "1.0p,1.0q,-1", "1.0p,1.0_p,1", "1.0a_rc1,1.0a,-1", "1.0a_p1,1.0a,1",
            "1.0a_p1-r1,1.0a_p1-r2,-1", "1.0a_p1,1.0b_alpha,-1",
            "1.0_alpha1_p1,1.0_alpha2,-1", "1.0_alpha_p1,1.0_alpha0,1",
            "1.0_alpha_alpha,1.0_alpha0,-1", "1.0_p_alpha,1.0_p,-1", "1.0_p_p,1.0_p,1",
            "1.0a_rc1_p2-r1,1.0a_rc1_p2-r2,-1", "1.0_p1_alpha,1.0_p1-r0,-1",
            "1.0_p1_p,1.0_p1-r0,1"})
    void numericSpellingAndPresenceRetainTheirOrdering(String left, String right, int sign) {
        assertThat(Integer.signum(ApkVersionComparator.compare(left, right))).isEqualTo(sign);
        assertThat(Integer.signum(ApkVersionComparator.compare(right, left))).isEqualTo(-sign);
    }

    @ParameterizedTest
    @CsvSource({"1.01,1.1,true", "1.02,1.010,false", "1.0,1.00,true", "1.2,1.10,true",
            "1,1.0,true", "1.0,1.0.0,true", "1.0,1.0-r0,true", "1.0_p,1.0_p0,true", "1.0_rc,1.0_rc0,true", "1.0a_rc1,1.0a,true",
            "1.0a_p1,1.0a,false", "1.0a_p1-r1,1.0a_p1-r2,true",
            "1.0_alpha1_p1,1.0_alpha2,true", "1.0_alpha_p1,1.0_alpha0,false",
            "1.0_p_alpha,1.0_p,true", "1.0a_rc1_p2-r1,1.0a_rc1_p2-r2,true"})
    void numericSpellingAndPresenceReachRangeAndBulkFixDecisions(String installed, String fixed, boolean affected) throws Exception {
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
