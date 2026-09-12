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
    @ValueSource(strings = {"hash", "lines", "extra", "missing", "no-files", "null-files", "version", "valid"})
    void manifestMustDescribeBaselineBytes(String damage, @TempDir Path directory) throws Exception {
        byte[] content = "{\"cveId\":\"CVE-2026-1000\"}\n".getBytes(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        var meta = mapper.createObjectNode();
        meta.put("formatVersion", 2);
        meta.put("mode", "full");
        var files = meta.putObject("files");
        var file = files.putObject("kev.jsonl");
        file.put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content)));
        file.put("lines", 1);
        switch (damage) {
            case "hash" -> file.put("sha256", "0".repeat(64));
            case "lines" -> file.put("lines", 2);
            case "extra" -> files.set("missing.jsonl", file.deepCopy());
            case "missing" -> files.remove("kev.jsonl");
            case "no-files" -> meta.remove("files");
            case "null-files" -> meta.putNull("files");
            case "version" -> meta.put("formatVersion", 999);
        }
        Path previous = directory.resolve("base.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(previous))) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(mapper.writeValueAsBytes(meta));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("kev.jsonl"));
            zip.write(content);
            zip.closeEntry();
        }
        if (damage.equals("valid")) {
            assertThat(PreviousBundleReader.read(previous, mapper).linesByFileAndKey().get("kev.jsonl"))
                    .containsOnlyKeys("CVE-2026-1000");
        } else {
            assertThatThrownBy(() -> PreviousBundleReader.read(previous, mapper)).isInstanceOf(IOException.class);
            Path output = directory.resolve("output.zip");
            Files.writeString(output, "existing output");
            assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "osv", "--since", previous.toString(),
                    "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
            assertThat(Files.readString(output)).isEqualTo("existing output");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{} {}", "{\"mode\":\"delta\"}", "{\"mode\":null}",
            "{\"mode\":12}", "{\"mode\":\"unknown\"}", "{\"mode\":\"full\",\"mode\":\"full\"}"})
    void invalidMetadataCannotActAsCompleteBaseline(String meta, @TempDir Path directory) throws Exception {
        Path previous = directory.resolve("base.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(previous))) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThatThrownBy(() -> PreviousBundleReader.read(previous, new ObjectMapper())).isInstanceOf(IOException.class);
        Path output = directory.resolve("output.zip");
        Files.writeString(output, "existing output");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "osv", "--since", previous.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"mode\":\"full\",\"bundleId\":\"baseline\"}"})
    void fullAndLegacyMetadataRemainReadable(String meta, @TempDir Path directory) throws Exception {
        Path previous = directory.resolve("base.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(previous))) {
            zip.putNextEntry(new ZipEntry("meta.json"));
            zip.write(meta.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertThat(PreviousBundleReader.read(previous, new ObjectMapper())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"truncated", "not-zip", "duplicate", "crc"})
    void incompleteOrAmbiguousArchiveCannotReplaceOutput(String damage, @TempDir Path directory) throws Exception {
        Path previous = baseline(directory, "{\"cveId\":\"CVE-2026-1000\"}");
        if (damage.equals("duplicate")) {
            try (var zip = new ZipOutputStream(Files.newOutputStream(previous))) {
                for (String name : new String[]{"kev.jsonl", "foo.jsonl"}) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write("{\"cveId\":\"CVE-2026-1000\"}".getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            byte[] bytes = Files.readAllBytes(previous);
            byte[] from = "foo.jsonl".getBytes(StandardCharsets.US_ASCII);
            byte[] to = "kev.jsonl".getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i <= bytes.length - from.length; i++) {
                if (java.util.Arrays.equals(bytes, i, i + from.length, from, 0, from.length))
                    System.arraycopy(to, 0, bytes, i, to.length);
            }
            Files.write(previous, bytes);
        } else if (damage.equals("crc")) {
            byte[] bytes = Files.readAllBytes(previous);
            var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            boolean changed = false;
            for (int i = 0; i < bytes.length - 20; i++) {
                if (buffer.getInt(i) == 0x02014b50) { bytes[i + 16] ^= 1; changed = true; break; }
            }
            assertThat(changed).isTrue();
            Files.write(previous, bytes);
        } else if (damage.equals("truncated")) {
            byte[] bytes = Files.readAllBytes(previous);
            Files.write(previous, java.util.Arrays.copyOf(bytes, bytes.length - 22));
        } else Files.writeString(previous, "not a zip archive");
        assertThatThrownBy(() -> PreviousBundleReader.read(previous, new ObjectMapper())).isInstanceOf(IOException.class);
        Path output = directory.resolve("output.zip");
        Files.writeString(output, "existing output");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "osv", "--since", previous.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing output");
    }

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
