package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OsvBulkRangeIntegrationTest {
    private final OsvBulkSource source = new OsvBulkSource(new ObjectMapper());

    @Test
    void withdrawnEntryDoesNotProduceAnActiveFinding() {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-withdrawn","withdrawn":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """, Set.of("1.0.0"), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "\"9999-01-01T00:00:00Z\""})
    void malformedWithdrawalCannotProduceAConfirmedCleanResult(String withdrawn) {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-invalid","withdrawn":%s,"affected":[{
                "package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """.formatted(withdrawn), Set.of("1.0.0"), findings, unknown);
        assertThat(unknown).containsExactly(key("1.0.0"));
    }

    @Test
    void unionMatchesAreActuallyWrittenToTheSnapshotResults() {
        String advisory = """
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "versions":["9.0.0"],"ranges":[{"type":"SEMVER","events":[
                {"introduced":"0"},{"fixed":"1.0.0"},{"introduced":"2.0.0"},{"fixed":"3.0.0"}]}]}]}
                """;
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(advisory, Set.of("0.5.0", "1.5.0", "2.5.0", "9.0.0"), findings, unknown);
        assertThat(findings.keySet()).containsExactlyInAnyOrder(key("0.5.0"), key("2.5.0"), key("9.0.0"));
        assertThat(unknown).isEmpty();
    }

    @Test
    void gitAncestryWithoutEvidenceIsPreservedAsUnresolvedCoverage() {
        String advisory = """
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"GIT","repo":"https://example.invalid/repo","events":[{"introduced":"0"}]}]}]}
                """;
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(advisory, Set.of("1.0.0"), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).containsExactly(key("1.0.0"));
    }

    private void process(String advisory, Set<String> wanted, Map<String, List<SnapshotVuln>> findings, Set<String> unknown) {
        ReflectionTestUtils.invokeMethod(source, "processVulnEntry", advisory.getBytes(StandardCharsets.UTF_8),
                "NPM", Map.of("example", wanted), findings, unknown);
    }

    @Test
    void confirmedUnionDoesNotEraseUncertaintyFromAnotherAdvisory() {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-unresolved","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"GIT","events":[{"introduced":"0"}]}]}]}
                """, Set.of("1.0.0"), findings, unknown);
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-confirmed","affected":[
                {"package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]},
                {"package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """, Set.of("1.0.0"), findings, unknown);
        assertThat(unknown).containsExactly(key("1.0.0"));
        assertThat(findings.get(key("1.0.0"))).singleElement()
                .extracting(SnapshotVuln::osvId).isEqualTo("OSV-confirmed");
    }

    private String key(String version) {
        return AirgappedSnapshotService.componentKey("NPM", "example", version);
    }
}
