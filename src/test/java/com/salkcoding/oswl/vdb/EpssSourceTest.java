package com.salkcoding.oswl.vdb;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpssSourceTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"", "cve,epss,percentile\nCVE-2026-0001,0.5,0.5",
            "#score_date:2026-01-01\ncve,epss,percentile",
            "#score_date:broken\ncve,epss,percentile\nCVE-2026-0001,0.5,0.5",
            "#score_date:2099-01-01\ncve,epss,percentile\nCVE-2026-0001,0.5,0.5",
            "#score_date:2026-01-01garbage\ncve,epss,percentile\nCVE-2026-0001,0.5,0.5",
            "#score_date:2026-01-01\ncve,percentile,epss\nCVE-2026-0001,0.5,0.9",
            "#score_date:2026-01-01\n#score_date:2026-01-02\ncve,epss,percentile\nCVE-2026-0001,0.5,0.5"})
    void missingOrConflictingDatasetEvidenceIsRejected(String content) throws Exception {
        try (var output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("epss_scores-current.csv.gz")))) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
        }
        assertThatThrownBy(() -> new EpssSource().fetch(new HttpCache(directory, true))).isInstanceOf(IOException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-01-01", "2026-01-01T12:00:22Z", "2026-01-01T12:00:22+00:00"})
    void preservesTheSourceDate(String date) throws Exception {
        try (var output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("epss_scores-current.csv.gz")))) {
            output.write(("#model_version:test,score_date:" + date + "\ncve,epss,percentile\nCVE-2026-0001,0.5,0.5\n").getBytes(StandardCharsets.UTF_8));
        }
        org.assertj.core.api.Assertions.assertThat(new EpssSource().fetch(new HttpCache(directory, true)).asOf())
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.1", "1.1", "broken", "0", "0.5", "1"})
    void validatesProbabilitiesBeforeAcceptingDataset(String score) throws Exception {
        try (var output = new GZIPOutputStream(Files.newOutputStream(directory.resolve("epss_scores-current.csv.gz")))) {
            output.write(("#model_version:test,score_date:2026-01-01\ncve,epss,percentile\n"
                    + "CVE-2026-0001,0.5,0.5\nCVE-2026-0002," + score + ",0.5\n").getBytes(StandardCharsets.UTF_8));
        }
        if (java.util.List.of("0", "0.5", "1").contains(score)) {
            org.assertj.core.api.Assertions.assertThat(new EpssSource().fetch(new HttpCache(directory, true)).scores())
                    .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("CVE-2026-0001", 0.5, "CVE-2026-0002", Double.parseDouble(score)));
        } else {
            assertThatThrownBy(() -> new EpssSource().fetch(new HttpCache(directory, true))).isInstanceOf(IOException.class);
        }
    }
}
