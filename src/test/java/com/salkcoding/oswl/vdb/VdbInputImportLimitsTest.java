package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class VdbInputImportLimitsTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"line", "compression", "normal"})
    void verifyAndDeltaBaselineRejectUnimportableInput(String kind) throws Exception {
        var mapper = new ObjectMapper();
        String summary;
        if (kind.equals("line")) {
            var random = new Random(73);
            var text = new StringBuilder();
            for (int i = 0; i < 1024 * 1024 + 1; i++) text.append((char) ('a' + random.nextInt(26)));
            summary = text.toString();
        } else summary = kind.equals("compression") ? "x".repeat(200000) : "Synthetic normal finding";
        String line = mapper.writeValueAsString(Map.of("ecosystem", "NPM", "name", "fixture", "version", "1.0.0",
                "vulns", List.of(Map.of("osvId", "OSV-fixture", "summary", summary)))) + "\n";
        byte[] content = line.getBytes(StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String meta = mapper.writeValueAsString(Map.of("formatVersion", 2, "mode", "full", "files",
                Map.of("osv.jsonl", Map.of("sha256", hash, "lines", 1))));
        Path bundle = directory.resolve("bundle.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(bundle))) {
            for (var entry : Map.of("meta.json", meta, "osv.jsonl", line).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        byte[] before = Files.readAllBytes(bundle);
        int exit = new VdbBuilderCli().run(new String[] {"verify", bundle.toString()});
        if (kind.equals("normal")) {
            assertThat(exit).isZero();
            assertThat(PreviousBundleReader.read(bundle, mapper).linesByFileAndKey().get("osv.jsonl")).hasSize(1);
        } else {
            assertThat(exit).isEqualTo(1);
            assertThatThrownBy(() -> PreviousBundleReader.read(bundle, mapper)).isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining(kind.equals("line") ? "line" : "compression ratio");
        }
        assertThat(Files.readAllBytes(bundle)).isEqualTo(before);
    }
}
