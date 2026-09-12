package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class GoAdvisoryComparisonTest {
    @ParameterizedTest
    @CsvSource({"v1.2.3,1.2.3,AFFECTED", "1.2.3,v1.2.3,AFFECTED",
            "v1.2.3+incompatible,1.2.3,NOT_AFFECTED", "v1.2.3,1.2.4,NOT_AFFECTED",
            "v1.2.3,master,UNKNOWN"})
    void enumeratedGoVersionsRespectPrefixIdentity(String installed, String listed, OsvRangeEvaluator.Result expected) {
        assertThat(OsvRangeEvaluator.evaluate("GO", installed, java.util.Set.of(listed), null)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void anEnumeratedAliasCanInvalidateAFixCandidate() throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"affected":[{"package":{"ecosystem":"Go","name":"example.org/module"},"versions":["v1.2.4"],
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """);
        assertThat(OsvFixVersionSelector.select(advisory, "Go", "example.org/module", "v1.2.3").version()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"v1.2.3,true", "1.2.3,true", "v1.2.4-0.20260101000000-abcdefabcdef,true",
            "v1.2.4-rc.1,true", "v1.2.4,false", "1.2.4,false", "v1.2.4+incompatible,false", "v1.10.0,false"})
    void goOrderingIsSharedByOsvAndGithub(String installed, boolean affected) throws Exception {
        for (String rangeType : java.util.List.of("SEMVER", "ECOSYSTEM")) {
            var advisory = new ObjectMapper().readTree("""
                    {"id":"OSV-fixture","affected":[{"package":{"ecosystem":"Go","name":"example.org/module"},
                    "ranges":[{"type":"%s","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                    """.formatted(rangeType));
            assertThat(OsvRangeEvaluator.evaluate("GO", installed, null, advisory.path("affected").get(0).path("ranges")))
                    .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
            assertThat(OsvFixVersionSelector.select(advisory, "Go", "example.org/module", installed).version())
                    .isEqualTo(affected ? "1.2.4" : null);
        }
        Boolean github = ReflectionTestUtils.invokeMethod(GitHubAdvisoryClient.class, "isVersionAffected", "GO", installed, "< 1.2.4");
        assertThat(github).isEqualTo(affected);
        if (affected) {
            var finding = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", null, "fixture", null, null, null, "1.2.4");
            java.util.List<GitHubAdvisoryClient.GitHubAdvisory> confirmed = ReflectionTestUtils.invokeMethod(
                    GitHubAdvisoryClient.class, "confirmedFixes", "GO", installed, java.util.List.of(finding), java.util.List.of(finding),
                    java.util.Map.of("GHSA-fixture", java.util.List.of("< 1.2.4")));
            assertThat(confirmed).singleElement().extracting(GitHubAdvisoryClient.GitHubAdvisory::fixVersion).isEqualTo("1.2.4");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"v1.2", "master", "go1.22rc1", "V1.2.3", "1.2.3-01"})
    void unsupportedConcreteVersionsStayUnknown(String installed) throws Exception {
        var ranges = new ObjectMapper().readTree("[{\"type\":\"SEMVER\",\"events\":[{\"introduced\":\"0\"},{\"fixed\":\"1.2.4\"}]}]");
        assertThat(OsvRangeEvaluator.evaluate("GO", installed, null, ranges)).isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                GitHubAdvisoryClient.class, "isVersionAffected", "GO", installed, "< 1.2.4")).isInstanceOf(IllegalArgumentException.class);
    }
}
