package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class KevSourceDateTest {
    @ParameterizedTest
    @ValueSource(strings = {"count", "no-count", "count-type", "no-version", "version-type", "blank-version",
            "no-list", "list-type", "row-type", "cve-type", "cve-format", "duplicate"})
    void incompleteCatalogCannotReplaceBundle(String damage, @TempDir Path directory) throws Exception {
        var mapper = new ObjectMapper();
        var root = mapper.createObjectNode();
        root.put("dateReleased", "2020-01-01T00:00:00Z");
        root.put("catalogVersion", "fixture");
        root.put("count", 1);
        var rows = root.putArray("vulnerabilities");
        var row = rows.addObject().put("cveID", "CVE-2026-1000");
        switch (damage) {
            case "count" -> root.put("count", 2);
            case "no-count" -> root.remove("count");
            case "count-type" -> root.put("count", "1");
            case "no-version" -> root.remove("catalogVersion");
            case "version-type" -> root.put("catalogVersion", 1);
            case "blank-version" -> root.put("catalogVersion", " ");
            case "no-list" -> root.remove("vulnerabilities");
            case "list-type" -> root.putObject("vulnerabilities");
            case "row-type" -> { rows.removeAll(); rows.add(1); }
            case "cve-type" -> row.put("cveID", 1);
            case "cve-format" -> row.put("cveID", "not-a-cve");
            case "duplicate" -> { rows.add(row.deepCopy()); root.put("count", 2); }
        }
        Files.write(directory.resolve("kev.json"), mapper.writeValueAsBytes(root));
        assertThatThrownBy(() -> new KevSource(mapper).fetch(new HttpCache(directory, true))).isInstanceOf(IOException.class);
        Path output = directory.resolve("output.zip");
        Files.writeString(output, "existing output");
        assertThat(new VdbBuilderCli().run(new String[]{"build", "--sources", "kev", "--offline-sources", directory.toString(),
                "--out", output.toString()})).isEqualTo(1);
        assertThat(Files.readString(output)).isEqualTo("existing output");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "12", "\"\"", "\"broken\"", "\"2026-01-01\"", "\"2026-01-01junk\"", "\"2999-01-01T00:00:00Z\""})
    void invalidSourceDateCannotBecomeToday(String date, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("kev.json"), "{\"count\":1,\"catalogVersion\":\"fixture\",\"dateReleased\":" + date + ",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-1000\"}]}");
        assertThatThrownBy(() -> new KevSource(new ObjectMapper()).fetch(new HttpCache(directory, true)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void actualReleaseDateIsPreserved(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("kev.json"), "{\"count\":1,\"catalogVersion\":\"fixture\",\"dateReleased\":\"2020-01-01T23:59:59.123Z\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-1000\"}]}");
        var result = new KevSource(new ObjectMapper()).fetch(new HttpCache(directory, true));
        assertThat(result.asOf()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(result.cveIds()).containsExactly("CVE-2026-1000");
    }
}
