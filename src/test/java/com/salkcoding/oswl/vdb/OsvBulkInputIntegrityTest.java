package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OsvBulkInputIntegrityTest {
    @TempDir Path directory;
    private static final String VALID = """
            {"id":"OSV-fixture","modified":"2024-09-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"example"},
            "versions":["1.0.0"]}]}
            """;

    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "false", "1", "{}", "\"broken\"", "\"2999-01-01T00:00:00Z\""})
    void untrustedRevisionCannotEstablishActiveOrWithdrawnCoverage(String modified) throws Exception {
        for (boolean withdrawn : new boolean[] {false, true}) {
            var record = new ObjectMapper().readTree(VALID);
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) record;
            if (modified.equals("missing")) object.remove("modified");
            else object.set("modified", new ObjectMapper().readTree(modified));
            if (withdrawn) object.put("withdrawn", "2024-09-02T00:00:00Z");
            cache(zip(record.toString()), true);
            assertThatThrownBy(this::fetch).isInstanceOf(IOException.class).hasMessageContaining("revision");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "null", "[]", "{}", "{\"id\":\"OSV-fixture\",\"modified\":\"2024-09-01T00:00:00Z\",\"affected\":false}",
            "{\"id\":\"OSV-fixture\",\"modified\":\"2024-09-01T00:00:00Z\",\"affected\":[{}]}"})
    void invalidRecordAbortsCollectionInsteadOfConfirmingUnmatchedComponentsClean(String record) throws Exception {
        cache(zip(record), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "{}", "\"1.0.0\"", "[1]", "[null]", "[{}]", "[\"\"]", "[\" \"]", "[\"1.0.0\",false]"})
    void malformedVersionListsCannotEstablishCoverage(String versions) throws Exception {
        cache(zip(VALID.replace("[\"1.0.0\"]", versions)), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {"npm;absent;*;true", "cargo;absent;latest;true",
            "go;absent;master;true", "pypi;absent;>=1;true", "maven;absent;[1,2);true",
            "nuget;absent;1.*;true", "pypi;invalid/name;1.0.0;true", "npm;absent;1.0.0;false"}, delimiter = ';')
    void absentPackageCannotValidateAnInvalidWantedIdentity(String ecosystem, String name,
            String version, boolean unresolved) throws Exception {
        var cache = org.mockito.Mockito.mock(HttpCache.class);
        org.mockito.Mockito.when(cache.getOrFetchWithLastModified(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new HttpCache.FetchResult(zip(VALID), LocalDate.now()));
        var result = new OsvBulkSource(new ObjectMapper()).fetch(List.of(
                new WantedComponent(ecosystem, name, version), new WantedComponent("npm", "example", "1.0.0")), null, cache);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(ecosystem, name, version);
        assertThat(result.unresolvedKeys().contains(key)).isEqualTo(unresolved);
        assertThat(result.vulnsByComponentKey()).doesNotContainKey(key);
        assertThat(result.vulnsByComponentKey().get("NPM|example|1.0.0")).hasSize(1);
        assertThat(result.unresolvedKeys()).doesNotContain("NPM|example|1.0.0");
    }

    @Test void invalidWantedVersionIsPublishedAsUnresolvedByCli() throws Exception {
        cache(zip(VALID), true);
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, """
                {"ecosystem":"npm","name":"absent","version":"*"}
                {"ecosystem":"npm","name":"example","version":"1.0.0"}
                """);
        Path output = directory.resolve("identity-bundle.zip");
        assertThat(new VdbBuilderCli().run(new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isZero();
        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            String unresolved = new String(zip.getInputStream(zip.getEntry("unresolved.jsonl")).readAllBytes(), StandardCharsets.UTF_8);
            var row = new ObjectMapper().readTree(unresolved);
            assertThat(row.path("name").asText()).isEqualTo("absent");
            assertThat(row.path("version").asText()).isEqualTo("*");
            String osv = new String(zip.getInputStream(zip.getEntry("osv.jsonl")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(osv).contains("example").doesNotContain("absent");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,true,true", "true,true,false", "true,false,true", "true,false,false",
            "false,true,true", "false,true,false", "false,false,true", "false,false,false"})
    void explicitOsvUncertaintySurvivesOtherEvidence(boolean partial, boolean finding, boolean depsdev) {
        var wanted = new WantedComponent("npm", "example", "1.0.0");
        String key = "NPM|example|1.0.0";
        var versions = depsdev ? List.of(new DepsDevSource.VersionRecord("npm", "example", "1.0.0",
                List.of(), List.of(), false, null)) : List.<DepsDevSource.VersionRecord>of();
        Object result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(VdbBuilderCli.class,
                "partitionResolution", List.of(wanted), finding ? java.util.Set.of(key) : java.util.Set.of(),
                partial ? java.util.Set.of(key) : java.util.Set.of(), java.util.Set.of("NPM"), versions);
        List<WantedComponent> unresolved = org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "unresolved");
        Integer resolvedCount = org.springframework.test.util.ReflectionTestUtils.invokeMethod(result, "resolvedCount");
        assertThat(unresolved).isEqualTo(partial ? List.of(wanted) : List.of());
        assertThat(resolvedCount).isEqualTo(partial ? 0 : 1);
    }

    @Test void cliPreservesBothKnownFindingsAndUnresolvedAdvisories() throws Exception {
        var mapper = new ObjectMapper();
        var unknown = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(VALID);
        unknown.put("id", "OSV-unknown");
        var affected = (com.fasterxml.jackson.databind.node.ObjectNode) unknown.path("affected").get(0);
        affected.remove("versions");
        affected.putArray("ranges").addObject().put("type", "GIT").putArray("events")
                .addObject().put("introduced", "0");
        cache(zipRecords(List.of(VALID, unknown.toString())), true);
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, """
                {"ecosystem":"npm","name":"example","version":"1.0.0"}
                """);
        Path output = directory.resolve("partial-bundle.zip");
        assertThat(new VdbBuilderCli().run(new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isZero();
        try (var zip = new java.util.zip.ZipFile(output.toFile())) {
            var unresolved = mapper.readTree(zip.getInputStream(zip.getEntry("unresolved.jsonl")));
            assertThat(unresolved.path("name").asText()).isEqualTo("example");
            var osv = mapper.readTree(zip.getInputStream(zip.getEntry("osv.jsonl")));
            assertThat(osv.path("vulns")).hasSize(1);
            assertThat(osv.path("vulns").get(0).path("osvId").asText()).isEqualTo("OSV-fixture");
        }
    }

    @Test void nonZipResponseIsNotAnEmptySuccessfulDataset() throws Exception {
        cache("<html>upstream error</html>".getBytes(StandardCharsets.UTF_8), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @Test void emptyArchiveHasNoEvidenceOfCompleteCoverage() throws Exception {
        cache(zip(null), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"after-first-record", "before-directory", "inside-directory", "missing-end"})
    void truncatedArchiveCannotConfirmAnOmittedPackageClean(String truncation) throws Exception {
        byte[] complete = zipRecords(List.of(VALID.replace("example", "other").replace("OSV-fixture", "OSV-other"), VALID));
        int directoryOffset = signature(complete, 0x02014b50, 0);
        int cut = switch (truncation) {
            case "after-first-record" -> signature(complete, 0x04034b50, 4);
            case "before-directory" -> directoryOffset;
            case "inside-directory" -> directoryOffset + 10;
            default -> complete.length - 22;
        };
        cache(java.util.Arrays.copyOf(complete, cut), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}\n");
        Path output = directory.resolve("bundle.zip");
        Files.write(output, complete);
        assertThat(new VdbBuilderCli().run(new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readAllBytes(output)).isEqualTo(complete);
    }

    private static int signature(byte[] bytes, int signature, int start) {
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = start; i <= bytes.length - 4; i++) if (buffer.getInt(i) == signature) return i;
        throw new AssertionError("Missing fixture ZIP signature");
    }

    @ParameterizedTest
    @ValueSource(strings = {"crc", "size", "stored-content"})
    void inconsistentEntryMetadataCannotEstablishCoverage(String corruption) throws Exception {
        byte[] bytes = storedZip();
        int central = signature(bytes, 0x02014b50, 0);
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (corruption) {
            case "crc" -> buffer.putInt(central + 16, buffer.getInt(central + 16) ^ 1);
            case "size" -> buffer.putInt(central + 24, buffer.getInt(central + 24) + 1);
            default -> {
                int body = 30 + Short.toUnsignedInt(buffer.getShort(26)) + Short.toUnsignedInt(buffer.getShort(28));
                bytes[body + VALID.indexOf("example")] = 'z';
            }
        }
        cache(bytes, true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completeArchiveRetainsTheMatchingFindingAfterAnUnrelatedRecord(boolean stored) throws Exception {
        cache(stored ? storedZip() : zipRecords(List.of(
                VALID.replace("example", "other").replace("OSV-fixture", "OSV-other"), VALID)), true);
        var result = fetch();
        assertThat(result.unresolvedKeys()).isEmpty();
        assertThat(result.vulnsByComponentKey().values()).singleElement().satisfies(vulns ->
                assertThat(vulns).singleElement().extracting(v -> v.osvId()).isEqualTo("OSV-fixture"));
    }

    private byte[] storedZip() throws IOException {
        byte[] content = VALID.getBytes(StandardCharsets.UTF_8);
        var crc = new java.util.zip.CRC32();
        crc.update(content);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            var entry = new ZipEntry("fixture.json");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test void missingSourceDateMustNotBeReplacedWithToday() throws Exception {
        cache(zip(VALID), false);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class).hasMessageContaining("Last-Modified");
    }

    @Test void validCachedSourcePreservesItsActualDateAndFindings() throws Exception {
        cache(zip(VALID), true);
        var result = fetch();
        assertThat(result.asOfByBucket()).containsEntry("NPM", LocalDate.of(2024, 10, 1));
        assertThat(result.vulnsByComponentKey()).hasSize(1);
        assertThat(result.unresolvedKeys()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken", "null", "[false]", "[\"\"]"})
    void failedCollectionDoesNotOverwriteAnExistingBundle(String invalid) throws Exception {
        cache(zip(invalid.equals("broken") ? invalid : VALID.replace("[\"1.0.0\"]", invalid)), true);
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}\n");
        Path output = directory.resolve("bundle.zip");
        byte[] previous = zip(VALID);
        Files.write(output, previous);
        int exit = new VdbBuilderCli().run(new String[] {"build", "--sources", "osv",
                "--wanted", wanted.toString(), "--offline-sources", directory.toString(), "--out", output.toString()});
        assertThat(exit).isEqualTo(1);
        assertThat(Files.readAllBytes(output)).isEqualTo(previous);
    }

    @Test void validSourceStillBuildsAVerifiableBundle() throws Exception {
        cache(zip(VALID), true);
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}\n");
        Path output = directory.resolve("bundle.zip");
        var cli = new VdbBuilderCli();
        assertThat(cli.run(new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()})).isZero();
        assertThat(cli.run(new String[] {"verify", output.toString()})).isZero();
    }

    private OsvBulkSource.Result fetch() throws Exception {
        return new OsvBulkSource(new ObjectMapper()).fetch(
                List.of(new WantedComponent("npm", "example", "1.0.0")), null, new HttpCache(directory, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"revision", "withdrawal", "range", "summary"})
    void conflictingDuplicateOriginalsAbortWithoutReplacingTheExistingBundle(String change) throws Exception {
        var mapper = new ObjectMapper();
        var second = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(VALID);
        switch (change) {
            case "revision" -> second.put("modified", "2024-09-02T00:00:00Z");
            case "withdrawal" -> second.put("withdrawn", "2024-09-02T00:00:00Z");
            case "range" -> ((com.fasterxml.jackson.databind.node.ObjectNode) second.path("affected").get(0))
                    .putArray("versions").add("2.0.0");
            case "summary" -> second.put("summary", "different original");
        }
        for (boolean reverse : new boolean[] {false, true}) {
            cache(zipRecords(reverse ? List.of(second.toString(), VALID) : List.of(VALID, second.toString())), true);
            assertThatThrownBy(this::fetch).isInstanceOf(IOException.class).hasMessageContaining("Conflicting");
            Path wanted = directory.resolve("wanted.jsonl");
            Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}\n");
            Path output = directory.resolve("bundle.zip");
            byte[] previous = zip(VALID);
            Files.write(output, previous);
            assertThat(new VdbBuilderCli().run(new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                    "--offline-sources", directory.toString(), "--out", output.toString()})).isEqualTo(1);
            assertThat(Files.readAllBytes(output)).isEqualTo(previous);
        }
    }

    @Test void equivalentOriginalsAreWrittenOnlyOnceDespiteFormattingAndObjectKeyOrder() throws Exception {
        String reordered = """
                {"affected":[{"versions":["1.0.0"],"package":{"name":"example","ecosystem":"npm"}}],
                "modified":"2024-09-01T00:00:00Z","id":"OSV-fixture"}
                """;
        cache(zipRecords(List.of(VALID, reordered, VALID)), true);
        var result = fetch();
        assertThat(result.unresolvedKeys()).isEmpty();
        assertThat(result.vulnsByComponentKey().values()).singleElement().satisfies(vulns -> assertThat(vulns).hasSize(1));
    }

    private byte[] zipRecords(List<String> records) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int i = 0; i < records.size(); i++) {
                zip.putNextEntry(new ZipEntry(i + ".json"));
                zip.write(records.get(i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private void cache(byte[] bytes, boolean dated) throws IOException {
        Files.write(directory.resolve("osv-npm-all.zip"), bytes);
        if (dated) Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), "2024-10-01");
    }

    private byte[] zip(String record) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            if (record != null) {
                zip.putNextEntry(new ZipEntry("fixture.json"));
                zip.write(record.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
