package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class VdbOutputImportLimitsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"line", "compression", "normal"})
    void generatedBundleMustMeetImportLimitsBeforeReplacingOutput(String kind) throws Exception {
        String summary;
        if (kind.equals("line")) {
            var random = new Random(73);
            var text = new StringBuilder();
            for (int i = 0; i < 1024 * 1024 + 1; i++) text.append((char) ('a' + random.nextInt(26)));
            summary = text.toString();
        } else summary = kind.equals("compression") ? "x".repeat(200000) : "Synthetic normal finding";
        Path output = directory.resolve("bundle.zip");
        Files.writeString(output, "existing-output");
        var writer = new VdbBundleWriter(new ObjectMapper(), Set.of("osv"));
        org.assertj.core.api.ThrowableAssert.ThrowingCallable build = () -> writer.write(output,
                Map.of("NPM|fixture|1.0.0", List.of(new SnapshotVuln("OSV-fixture", null, summary, null, null))),
                LocalDate.now(), List.of(), List.of(), Map.of(), Map.of(), LocalDate.now(), Set.of(), LocalDate.now(),
                0, null, List.of(), null);
        if (kind.equals("normal")) {
            assertThatCode(build).doesNotThrowAnyException();
            assertThat(PreviousBundleReader.read(output, new ObjectMapper()).linesByFileAndKey().get("osv.jsonl")).hasSize(1);
        } else {
            assertThatThrownBy(build).hasMessageContaining(kind.equals("line") ? "line" : "compression ratio");
            assertThat(Files.readString(output)).isEqualTo("existing-output");
        }
        try (var files = Files.list(directory)) {
            assertThat(files.toList()).containsExactly(output);
        }
    }
}
