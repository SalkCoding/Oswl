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
