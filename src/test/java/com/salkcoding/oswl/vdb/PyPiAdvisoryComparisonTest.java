package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PyPiAdvisoryComparisonTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Friendly_Bard", "friendly.bard", "FRIENDLY--BARD"})
    void equivalentNamesMatchWithoutChangingSnapshotKeys(String wantedName) throws Exception {
        String raw = """
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"PyPI","name":"friendly-bard"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"1.0"}]}]}]}
                """;
        var mapper = new ObjectMapper();
        var findings = new java.util.LinkedHashMap<String, java.util.List<com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln>>();
        var unresolved = new java.util.LinkedHashSet<String>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "PYPI", java.util.Map.of(wantedName, java.util.Set.of("0.9")), findings, unresolved);
        assertThat(findings).containsKey("PYPI|" + wantedName + "|0.9");
        assertThat(unresolved).isEmpty();
        assertThat(OsvFixVersionSelector.select(mapper.readTree(raw), "PyPI", wantedName, "0.9").version()).isEqualTo("1.0");
    }

    @org.junit.jupiter.api.Test
    void fixedCandidateCannotConflictWithAnEquivalentListedVersion() throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"PyPI","name":"fixture"},
                "versions":["1.0.0"],"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"1.0"}]}]}]}
                """);
        var result = OsvFixVersionSelector.select(advisory, "PyPI", "fixture", "0.9");
        assertThat(result.version()).isNull();
        assertThat(result.reason()).isEqualTo("FIX_CONFLICTS_WITH_AFFECTED_DATA");
    }

    @ParameterizedTest
    @CsvSource({"1.0.dev1,true", "1.0a1,true", "1.0b1,true", "1.0rc1,true", "1.0,false",
            "1.0.post1,false", "1.0+vendor.1,false", "1!0.1,false"})
    void pep440OrderingIsSharedByAdvisoryPaths(String installed, boolean affected) throws Exception {
        var advisory = new ObjectMapper().readTree("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"PyPI","name":"fixture"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"1.0"}]}]}]}
                """);
        assertThat(OsvRangeEvaluator.evaluate("PYPI", installed, null, advisory.path("affected").get(0).path("ranges")))
                .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(advisory, "PyPI", "fixture", installed).version()).isEqualTo(affected ? "1.0" : null);
        Boolean github = ReflectionTestUtils.invokeMethod(GitHubAdvisoryClient.class, "isVersionAffected", "PIP", installed, "< 1.0");
        assertThat(github).isEqualTo(affected);
    }
}
