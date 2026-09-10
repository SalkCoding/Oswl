package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryScoreBoundsTest {
    @ParameterizedTest
    @CsvSource({"0,true", "10,true", "7.5,true", "-1,false", "11,false", "NaN,false", "Infinity,false", "-Infinity,false"})
    void resultBoundariesKeepIndependentSeverityAndWithholdBadScores(double score, boolean valid) {
        Double expected = valid ? score : null;
        var osv = new OsvClient.OsvVuln("GHSA-fixture", "CVE-2026-0001", "Evidence", "2.0", null,
                RiskLevel.HIGH, score, null);
        var ghsa = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", "CVE-2026-0001", "Evidence",
                RiskLevel.HIGH, score, null, "2.0");
        var nvd = new NvdClient.NvdCve("CVE-2026-0001", "Evidence", RiskLevel.HIGH, score, null, null);
        assertThat(osv.cvssScore()).isEqualTo(expected);
        assertThat(ghsa.cvssScore()).isEqualTo(expected);
        assertThat(nvd.cvssScore()).isEqualTo(expected);
        assertThat(osv.effectiveSeverity()).isEqualTo(RiskLevel.HIGH);
        assertThat(ghsa.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(nvd.severity()).isEqualTo(RiskLevel.HIGH);
        assertThat(osv.fixVersion()).isEqualTo("2.0");
        assertThat(ghsa.fixVersion()).isEqualTo("2.0");
    }

    @ParameterizedTest
    @CsvSource({"0,NONE", "10,CRITICAL", "7.5,HIGH", "-1,NONE", "11,NONE", "NaN,NONE", "Infinity,NONE", "-Infinity,NONE"})
    void rawParsersDoNotDeriveSeverityFromInvalidScores(double score, RiskLevel expected) {
        Map<String, Object> cve = Map.of("id", "CVE-2026-0001", "metrics",
                Map.of("cvssMetricV31", List.of(Map.of("cvssData", Map.of("baseScore", score)))));
        List<NvdClient.NvdCve> nvd = ReflectionTestUtils.invokeMethod(new NvdClient(), "parseBody",
                Map.of("vulnerabilities", List.of(Map.of("cve", cve))), null);
        GitHubAdvisoryClient.GitHubAdvisory ghsa = ReflectionTestUtils.invokeMethod(new GitHubAdvisoryClient(),
                "parseAdvisoryNode", Map.of("advisory", Map.of("cvssSeverities", Map.of("cvssV3", Map.of("score", score)))));
        assertThat(nvd).hasSize(1);
        assertThat(nvd.getFirst().severity()).isEqualTo(expected);
        assertThat(ghsa.severity()).isEqualTo(expected);
        Double expectedScore = Double.isFinite(score) && score >= 0 && score <= 10 ? score : null;
        assertThat(nvd.getFirst().cvssScore()).isEqualTo(expectedScore);
        assertThat(ghsa.cvssScore()).isEqualTo(expectedScore);
    }
}
