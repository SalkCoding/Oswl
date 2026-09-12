package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class PreviousBundleIntegrityTest {
    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "null", "[]", "{\"cveId\":123}",
            "{\"cveId\":\"CVE-2026-1000\"} {}",
            "{\"cveId\":\"CVE-2026-1000\",\"cveId\":\"CVE-2026-1001\"}",
            "{\"cveId\":\"CVE-2026-1000\"}\n{\"cveId\":\"CVE-2026-1000\"}"})
    void invalidBaselineCannotProducePartialDelta(String row, @TempDir Path directory) throws Exception {
        Path previous = baseline(directory, row);
        assertThatThrownBy(() -> PreviousBundleReader.read(previous, new ObjectMapper())).isInstanceOf(IOException.class);
        Path output = directory.resolve("output.zip");
        Files.writeString(output, "existing output");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "osv", "--since", previous.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing output");
    }

    @Test
    void completeRowsAndBlankLinesRemainReadable(@TempDir Path directory) throws Exception {
        Path previous = baseline(directory, "\n{\"cveId\":\"CVE-2026-1000\"}\n\n");
        assertThat(PreviousBundleReader.read(previous, new ObjectMapper()).linesByFileAndKey().get("kev.jsonl"))
                .containsOnlyKeys("CVE-2026-1000");
    }

    private Path baseline(Path directory, String row) throws Exception {
        Path previous = directory.resolve("base.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(previous))) {
            zip.putNextEntry(new ZipEntry("kev.jsonl"));
            zip.write(row.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return previous;
    }
}
