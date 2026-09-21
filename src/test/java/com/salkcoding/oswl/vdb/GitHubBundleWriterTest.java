package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.*;

class GitHubBundleWriterTest {
    @TempDir Path directory;

    @Test
    void fullWriterPreservesLifecycleEmptyResultsUncertaintyAndSourceDate() throws Exception {
        var mapper = new ObjectMapper();
        var row = new SnapshotVuln("GHSA-fixture", null, "fixture", null, null, null, null, null, null,
                Set.of(), null, null, "2026-02-01T00:00:00Z", "9999-01-01T00:00:00Z");
        var data = new VdbBundleWriter.GitHubData(Map.of("NPM|fixture|1", List.of(row), "NPM|empty|1", List.of()), LocalDate.of(2026, 2, 2));
        var writer = new VdbBundleWriter(mapper, Set.of("github-advisory"), "unreviewed", data);
        Path output = directory.resolve("full.zip");
        write(writer, output, null);
        var baseline = PreviousBundleReader.read(output, mapper);
        assertThat(baseline.linesByFileAndKey().get("github-advisory.jsonl")).hasSize(2);
        var stored = mapper.readTree(baseline.linesByFileAndKey().get("github-advisory.jsonl").get("NPM|fixture|1")).path("vulns").get(0);
        assertThat(stored.path("githubUpdatedAt").asText()).isEqualTo(row.githubUpdatedAt());
        assertThat(stored.path("githubWithdrawnAt").asText()).isEqualTo(row.githubWithdrawnAt());
        assertThat(stored.path("fixVersion").isNull()).isTrue();
        assertThat(baseline.linesByFileAndKey().get("unresolved.jsonl")).containsKey("NPM|fixture|1");
        try (var zip = new ZipFile(output.toFile())) {
            var meta = mapper.readTree(zip.getInputStream(zip.getEntry("meta.json")));
            assertThat(meta.path("sources").path("github-advisory").path("asOf").asText()).isEqualTo("2026-02-02");
            assertThat(meta.path("dataNotices").path("githubAdvisoryDatabase").path("license").asText()).isEqualTo("CC-BY-4.0");
            assertThat(zip.getEntry("osv.jsonl")).isNull();
        }
        Path delta = directory.resolve("delta.zip");
        Files.writeString(delta, "existing output");
        assertThatThrownBy(() -> write(writer, delta, baseline)).isInstanceOf(java.io.IOException.class).hasMessageContaining("GitHub delta");
        assertThat(Files.readString(delta)).isEqualTo("existing output");
    }

    private void write(VdbBundleWriter writer, Path output, PreviousBundleReader.PreviousBundle previous) throws Exception {
        writer.write(output, Map.of(), LocalDate.now(), List.of(), List.of(), Map.of(), Map.of(), LocalDate.now(),
                Set.of(), LocalDate.now(), 1, new VdbBundleWriter.WantedListInfo("fixture-inventory", 2, 1),
                List.of(new WantedComponent("NPM", "fixture", "1")), previous);
    }
}
