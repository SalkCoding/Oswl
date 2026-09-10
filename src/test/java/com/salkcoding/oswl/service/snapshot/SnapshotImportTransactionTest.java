package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.snapshot.SnapshotEntryRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:snapshot-budget;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT")
class SnapshotImportTransactionTest {
    @Autowired AirgappedSnapshotService service;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired DataSource dataSource;

    @BeforeEach void existingSource() {
        entries.deleteAllInBatch();
        metadata.deleteAllInBatch();
        entries.save(SnapshotEntry.builder().source("epss").entryKey("CVE-OLD").payload("0.25").build());
    }

    @Test void importsMultipleChunksAndCleansStagingFiles() throws Exception {
        Set<Path> before = stagedFiles();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 5000; i++) lines.append("{\"cveId\":\"CVE-NEW-").append(i).append("\",\"score\":0.75}\n");
        byte[] zip = bundle(Map.of("epss.jsonl", lines.toString()));
        long start = System.nanoTime();
        var result = service.importBundle(new ByteArrayInputStream(zip));
        assertThat(result.totalRecords()).isEqualTo(5000);
        assertThat(entries.countBySource("epss")).isEqualTo(5000);
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-NEW-4999"))).containsOnly(entry("CVE-NEW-4999", .75));
        assertThat(stagedFiles()).isEqualTo(before);
        Path report = Path.of("build/reports/performance/snapshot.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "5000 records, chunk=500, elapsed_ms=" + (System.nanoTime()-start)/1_000_000 + "\n");
    }

    @Test void checksumAtEndRejectsBeforeReplaceAndCleansFiles() throws Exception {
        Set<Path> before = stagedFiles();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}\n");
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"wrong\"}}}");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("integrity");
        assertOldSource();
        assertThat(stagedFiles()).isEqualTo(before);
    }

    @Test void interruptedUploadDoesNotAcquireConnectionOrMutateSource() throws Exception {
        Set<Path> before = stagedFiles();
        byte[] zip = bundle(Map.of("epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}\n"));
        int activeBefore = ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
        InputStream input = new ByteArrayInputStream(zip) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isEqualTo(activeBefore);
                Thread.currentThread().interrupt();
                return super.read(b, off, len);
            }
        };
        try {
            assertThatThrownBy(() -> service.importBundle(input)).isInstanceOf(InvalidRequestException.class);
        } finally { Thread.interrupted(); }
        assertOldSource();
        assertThat(stagedFiles()).isEqualTo(before);
    }

    @Test void databaseFailureAfterFirstChunkRollsBackReplace() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 600; i++) lines.append("{\"cveId\":\"CVE-NEW-").append(i).append("\",\"score\":0.75}\n");
        // Exceeds entry_key length after the first chunk has already been flushed.
        lines.append("{\"cveId\":\"").append("X".repeat(2000)).append("\",\"score\":0.8}\n");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",lines.toString())))))
                .isInstanceOf(RuntimeException.class);
        assertOldSource();
    }

    private void assertOldSource() {
        assertThat(entries.countBySource("epss")).isEqualTo(1);
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsEntry("CVE-OLD", .25);
        assertThat(metadata.count()).isZero();
    }

    private Set<Path> stagedFiles() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("oswl-snapshot-")).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static byte[] bundle(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
