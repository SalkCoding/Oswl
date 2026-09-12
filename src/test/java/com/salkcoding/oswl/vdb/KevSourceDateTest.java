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
    @ValueSource(strings = {"null", "12", "\"\"", "\"broken\"", "\"2026-01-01\"", "\"2026-01-01junk\"", "\"2999-01-01T00:00:00Z\""})
    void invalidSourceDateCannotBecomeToday(String date, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("kev.json"), "{\"dateReleased\":" + date + ",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-1000\"}]}");
        assertThatThrownBy(() -> new KevSource(new ObjectMapper()).fetch(new HttpCache(directory, true)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void actualReleaseDateIsPreserved(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("kev.json"), "{\"dateReleased\":\"2020-01-01T23:59:59.123Z\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-1000\"}]}");
        var result = new KevSource(new ObjectMapper()).fetch(new HttpCache(directory, true));
        assertThat(result.asOf()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(result.cveIds()).containsExactly("CVE-2026-1000");
    }
}
