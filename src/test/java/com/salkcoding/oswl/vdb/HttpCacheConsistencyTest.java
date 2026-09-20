package com.salkcoding.oswl.vdb;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCacheConsistencyTest {
    @TempDir Path directory;

    @Test void undatedReplacementMustNotInheritPreviousSourceDateOnOfflineRead() throws Exception {
        seed();
        withResponse(200, null, url -> {
            var fetched = new HttpCache(directory).getOrFetchWithLastModified("source", url);
            assertThat(fetched.lastModified()).isNull();
            var offline = new HttpCache(directory, true).getOrFetchWithLastModified("source", url);
            assertThat(offline.body()).isEqualTo("new".getBytes(StandardCharsets.UTF_8));
            assertThat(offline.lastModified()).isNull();
        });
    }

    @Test void datedReplacementSurvivesOfflineRead() throws Exception {
        seed();
        withResponse(200, "Tue, 01 Oct 2024 00:00:00 GMT", url -> {
            new HttpCache(directory).getOrFetchWithLastModified("source", url);
            var offline = new HttpCache(directory, true).getOrFetchWithLastModified("source", url);
            assertThat(offline.body()).isEqualTo("new".getBytes(StandardCharsets.UTF_8));
            assertThat(offline.lastModified()).isEqualTo(LocalDate.of(2024, 10, 1));
        });
    }

    @Test void mismatchedBodyCannotReuseADateFromAnotherGeneration() throws Exception {
        withResponse(200, "Tue, 01 Oct 2024 00:00:00 GMT", url -> {
            new HttpCache(directory).getOrFetchWithLastModified("source", url);
            Files.writeString(directory.resolve("source"), "different-generation");
            assertThat(new HttpCache(directory, true).getOrFetchWithLastModified("source", url).lastModified()).isNull();
        });
    }

    @Test void failedRefreshPreservesThePreviousBodyAndDate() throws Exception {
        seed();
        withResponse(503, null, url -> {
            assertThatThrownBy(() -> new HttpCache(directory).getOrFetchWithLastModified("source", url))
                    .isInstanceOf(java.io.IOException.class);
            var previous = new HttpCache(directory, true).getOrFetchWithLastModified("source", url);
            assertThat(previous.body()).isEqualTo("old".getBytes(StandardCharsets.UTF_8));
            assertThat(previous.lastModified()).isEqualTo(LocalDate.of(2020, 1, 1));
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {3600, 172800})
    void futureHttpDateCannotBecomeFreshEvidence(long seconds) throws Exception {
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.now().plusSeconds(seconds).atZone(java.time.ZoneOffset.UTC));
        withResponse(200, date, url -> {
            assertThat(new HttpCache(directory).getOrFetchWithLastModified("source", url).lastModified()).isNull();
            assertThat(new HttpCache(directory, true).getOrFetchWithLastModified("source", url).lastModified()).isNull();
        });
    }

    @Test void futureLegacyDateCannotBecomeFreshEvidence() throws Exception {
        Files.writeString(directory.resolve("source"), "old");
        Files.writeString(directory.resolve("source.lastmodified"), LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2).toString());
        assertThat(new HttpCache(directory, true).getOrFetchWithLastModified("source", "unused").lastModified()).isNull();
    }

    @Test void httpDateUsesUtcCalendarDay() throws Exception {
        withResponse(200, "Sat, 02 Jan 2010 00:30:00 +1400", url -> {
            assertThat(new HttpCache(directory).getOrFetchWithLastModified("source", url).lastModified()).isEqualTo(LocalDate.of(2010, 1, 1));
            assertThat(new HttpCache(directory, true).getOrFetchWithLastModified("source", url).lastModified()).isEqualTo(LocalDate.of(2010, 1, 1));
        });
    }

    private void seed() throws Exception {
        Files.writeString(directory.resolve("source"), "old");
        Files.writeString(directory.resolve("source.lastmodified"), "2020-01-01");
        Files.setLastModifiedTime(directory.resolve("source"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
    }

    private void withResponse(int status, String date, RequestAction action) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data", exchange -> {
            if (date != null) exchange.getResponseHeaders().add("Last-Modified", date);
            byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try { action.run("http://127.0.0.1:" + server.getAddress().getPort() + "/data"); }
        finally { server.stop(0); }
    }

    @FunctionalInterface private interface RequestAction { void run(String url) throws Exception; }
}
