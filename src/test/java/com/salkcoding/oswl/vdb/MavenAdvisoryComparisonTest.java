package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MavenAdvisoryComparisonTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LATEST", "RELEASE"})
    void unresolvedRepositorySelectorsCannotBeComparedOrRecommended(String selector) throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"affected":[{"package":{"ecosystem":"Maven","name":"org.example:fixture"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2.0"}]}]}]}
                """);
        var ranges = advisory.path("affected").get(0).path("ranges");
        assertThat(OsvRangeEvaluator.evaluate("MAVEN", selector, null, ranges))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvRangeEvaluator.evaluate("MAVEN", selector, java.util.Set.of(selector), null))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvRangeEvaluator.evaluate("MAVEN", selector, java.util.Set.of("1.0"), null))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(advisory, "Maven", "org.example:fixture", selector).version()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                GitHubAdvisoryClient.class, "isVersionAffected", "MAVEN", selector, "< 2.0"))
                .isInstanceOf(IllegalArgumentException.class);

        var invalidFix = new ObjectMapper().readTree(advisory.toString().replace("2.0", selector));
        assertThat(OsvRangeEvaluator.evaluate("MAVEN", "1.0", null,
                invalidFix.path("affected").get(0).path("ranges"))).isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(invalidFix, "Maven", "org.example:fixture", "1.0").version()).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                GitHubAdvisoryClient.class, "isVersionAffected", "MAVEN", "1.0", "< " + selector))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"1.0-alpha1,true", "1.0-beta1,true", "1.0-RC1,true", "1.0-SNAPSHOT,true",
            "1.0,false", "1.0.Final,false", "1.0-ga,false", "1.0-sp1,false"})
    void qualifierOrderIsSharedByOsvFixSelectionAndGithub(String installed, boolean affected) throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"id":"OSV-fixture","affected":[{"package":{"ecosystem":"Maven","name":"org.example:fixture"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.0-alpha1"},{"fixed":"1.0"}]}]}]}
                """);
        var entry = advisory.path("affected").get(0);
        assertThat(OsvRangeEvaluator.evaluate("MAVEN", installed, null, entry.path("ranges")))
                .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(advisory, "Maven", "org.example:fixture", installed).version())
                .isEqualTo(affected ? "1.0" : null);
        Boolean github = ReflectionTestUtils.invokeMethod(GitHubAdvisoryClient.class, "isVersionAffected", "MAVEN",
                installed, ">= 1.0-alpha1, < 1.0");
        assertThat(github).isEqualTo(affected);
    }
}
