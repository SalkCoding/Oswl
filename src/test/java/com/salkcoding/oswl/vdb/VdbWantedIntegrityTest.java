package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VdbWantedIntegrityTest {
    private static final String VALID = "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}";

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"ecosystem\":\"npm\",\"name\":\"example\"}",
            "{\"ecosystem\":\"npm\",\"name\":12,\"version\":\"1\"}",
            "{\"ecosystem\":\"npm\",\"name\":\" \",\"version\":\"1\"}",
            "{\"ecosystem\":\"npm\",\"name\":\"a\",\"version\":\"1\",\"version\":\"2\"}",
            "{\"ecosystem\":\"npm\",\"name\":\"a\",\"version\":\"1\"} {}"})
    void invalidRowsCannotSilentlyReduceWantedCoverage(String row, @TempDir Path directory) throws Exception {
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, VALID + "\n" + row + "\n" + VALID);
        var method = VdbBuilderCli.class.getDeclaredMethod("loadWantedList", Path.class);
        method.setAccessible(true);
        assertThatThrownBy(() -> method.invoke(new VdbBuilderCli(), wanted)).hasCauseInstanceOf(IOException.class);
        Path output = directory.resolve("output.zip");
        Files.writeString(output, "existing output");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing output");
    }

    @Test
    void completeIdentityAndBlankLinesRemainReadable(@TempDir Path directory) throws Exception {
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "\n" + VALID + "\n\n");
        var method = VdbBuilderCli.class.getDeclaredMethod("loadWantedList", Path.class);
        method.setAccessible(true);
        assertThat(method.invoke(new VdbBuilderCli(), wanted)).isEqualTo(List.of(new WantedComponent("npm", "example", "1.0.0")));
    }
}
