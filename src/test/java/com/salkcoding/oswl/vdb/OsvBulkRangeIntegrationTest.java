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
    void unionMatchesAreActuallyWrittenToTheSnapshotResults() {
        String advisory = """
                {"id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
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
                {"id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
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

    private String key(String version) {
        return AirgappedSnapshotService.componentKey("NPM", "example", version);
    }
}
