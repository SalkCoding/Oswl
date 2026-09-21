package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VdbDeltaScopeTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"failed", "partial", "empty"})
    void unresolvedRefreshPreservesPriorFindingsWhileConfirmedEmptyCanReplaceThem(String state) throws Exception {
        var mapper = new ObjectMapper();
        var writer = new VdbBundleWriter(mapper, Set.of("osv"));
        var old = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("GHSA-old", null, "old", "2.0.0", null);
        var fresh = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("GHSA-new", null, "new", "3.0.0", null);
        var info = new VdbBundleWriter.WantedListInfo("same-inventory", 1, 1);
        Path base = directory.resolve("base.zip");
        writer.write(base, Map.of("NPM|fixture|1", List.of(old)), LocalDate.now(), List.of(), List.of(), Map.of(), Map.of(),
                LocalDate.now(), Set.of(), LocalDate.now(), 0, info, List.of(), null);
        var previous = PreviousBundleReader.read(base, mapper);
        Path delta = directory.resolve("delta.zip");
        boolean unresolved = !state.equals("empty");
        Map<String, List<com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln>> current = state.equals("failed")
                ? Map.of() : Map.of("NPM|fixture|1", state.equals("partial") ? List.of(fresh) : List.of());
        writer.write(delta, current, LocalDate.now(), List.of(), List.of(), Map.of(), Map.of(), LocalDate.now(), Set.of(),
                LocalDate.now(), unresolved ? 1 : 0, info,
                unresolved ? List.of(new WantedComponent("NPM", "fixture", "1")) : List.of(), previous);
        try (var zip = new java.util.zip.ZipFile(delta.toFile())) {
            String payload = new String(zip.getInputStream(zip.getEntry("osv.jsonl")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (state.equals("failed")) assertThat(payload).isEmpty();
            else {
                var row = mapper.readTree(payload);
                assertThat(row.path("_deleted").asBoolean()).isFalse();
                var ids = new ArrayList<String>();
                row.path("vulns").forEach(v -> ids.add(v.path("osvId").asText()));
                if (state.equals("partial")) assertThat(ids).containsExactlyInAnyOrder("GHSA-old", "GHSA-new");
                else assertThat(ids).isEmpty();
            }
            assertThat(zip.getEntry("unresolved.jsonl") != null).isEqualTo(unresolved);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"different", "missing", "same", "global"})
    void scopeChangesCannotDeletePreviouslyCollectedComponents(String mode) throws Exception {
        var mapper = new ObjectMapper();
        var writer = new VdbBundleWriter(mapper, Set.of("osv"));
        Path base = directory.resolve("base.zip");
        var baselineInfo = mode.equals("missing") ? null : new VdbBundleWriter.WantedListInfo("original", 2, 2);
        writer.write(base, Map.of("NPM|one|1", List.of(), "NPM|two|1", List.of()), LocalDate.now(),
                List.of(), List.of(), Map.of(), Map.of(), LocalDate.now(), Set.of(), LocalDate.now(),
                0, baselineInfo, List.of(), null);
        var previous = PreviousBundleReader.read(base, mapper);
        Path output = directory.resolve("delta.zip");
        Files.writeString(output, "keep-existing-output");
        var info = new VdbBundleWriter.WantedListInfo(mode.equals("same") ? "original" : "changed", 1, 1);
        var selectedWriter = mode.equals("global") ? new VdbBundleWriter(mapper, Set.of("kev")) : writer;
        org.assertj.core.api.ThrowableAssert.ThrowingCallable build = () -> selectedWriter.write(output,
                Map.of("NPM|one|1", List.of()), LocalDate.now(), List.of(), List.of(), Map.of(), Map.of(),
                LocalDate.now(), Set.of(), LocalDate.now(), 0, info, List.of(), previous);
        if (mode.equals("different") || mode.equals("missing")) {
            assertThatThrownBy(build).isInstanceOf(java.io.IOException.class).hasMessageContaining("wanted");
            assertThat(Files.readString(output)).isEqualTo("keep-existing-output");
        } else assertThatCode(build).doesNotThrowAnyException();
    }
}
