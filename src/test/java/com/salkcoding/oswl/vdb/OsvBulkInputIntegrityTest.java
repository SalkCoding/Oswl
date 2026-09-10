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

    @Test void nonZipResponseIsNotAnEmptySuccessfulDataset() throws Exception {
        cache("<html>upstream error</html>".getBytes(StandardCharsets.UTF_8), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
    }

    @Test void emptyArchiveHasNoEvidenceOfCompleteCoverage() throws Exception {
        cache(zip(null), true);
        assertThatThrownBy(this::fetch).isInstanceOf(IOException.class);
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
