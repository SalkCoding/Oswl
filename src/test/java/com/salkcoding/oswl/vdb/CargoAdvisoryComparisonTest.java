package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CargoAdvisoryComparisonTest {
    @ParameterizedTest
    @CsvSource({"1.2.3,1.2.4,true", "1.2.4-rc.2,1.2.4-rc.10,true",
            "1.2.4-rc.10,1.2.4-rc.2,false", "1.2.4-rc.1,1.2.4,true",
            "1.2.4,1.2.4,false", "1.2.4+build.1,1.2.4,false", "1.10.0,1.2.4,false"})
    void explicitAdvisoryBoundariesUseSemver(String installed, String fixed, boolean affected) throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"affected":[{"package":{"ecosystem":"crates.io","name":"example-crate"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"%s"}]}]}]}
                """.formatted(fixed));
        assertThat(OsvRangeEvaluator.evaluate("CRATES.IO", installed, null,
                advisory.path("affected").get(0).path("ranges")))
                .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(advisory, "crates.io", "example-crate", installed).version())
                .isEqualTo(affected ? fixed : null);
        Boolean github = ReflectionTestUtils.invokeMethod(GitHubAdvisoryClient.class,
                "isVersionAffected", "RUST", installed, "< " + fixed);
        assertThat(github).isEqualTo(affected);
        if (affected) assertThat(confirmedFix(installed, fixed, List.of("< " + fixed))).isEqualTo(fixed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2", "master", "v1.2.3", "1.2.3-01", "^1.2.3"})
    void unresolvedRequirementsAndInvalidVersionsCannotSupplyAFix(String installed) {
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(GitHubAdvisoryClient.class,
                "isVersionAffected", "RUST", installed, "< 1.2.4"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(confirmedFix(installed, "1.2.4", List.of("< 1.2.4"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {">= 1.2.4, < 1.2.5", "^1.2.4"})
    void conflictingOrUnsupportedRangesWithholdAFix(String otherRange) {
        assertThat(confirmedFix("1.2.3", "1.2.4", List.of("< 1.2.4", otherRange))).isNull();
    }

    private static String confirmedFix(String installed, String candidate, List<String> ranges) {
        var finding = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", null, "fixture",
                null, null, null, candidate);
        List<GitHubAdvisoryClient.GitHubAdvisory> confirmed = ReflectionTestUtils.invokeMethod(
                GitHubAdvisoryClient.class, "confirmedFixes", "RUST", installed,
                List.of(finding), Map.of("GHSA-fixture", ranges));
        assertThat(confirmed).hasSize(1);
        return confirmed.getFirst().fixVersion();
    }
}
