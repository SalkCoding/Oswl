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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "7,false", "8,true", "40,true", "-1,true", "999,true"})
    void offlineGithubRetainsFindingsButWithholdsStaleCoverageAndFixes(int age, boolean stale) {
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        String empty = AirgappedSnapshotService.componentKey("npm", "empty", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("github-advisory").entryKey(key)
                .payload("[{\"osvId\":\"GHSA-fixture\",\"fixVersion\":\"2.0.0\"}]").build());
        entries.saveAndFlush(SnapshotEntry.builder().source("github-advisory").entryKey(empty).payload("[]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("github-advisory")
                .recordCount(2).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var client = new com.salkcoding.oswl.client.GitHubAdvisoryClient(service, true, null,
                "https://api.github.com/graphql", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var stored = client.findByComponentKeys(List.of(key, empty));
        var source = new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(client);
        assertThat(stored).containsKeys(key, empty);
        var actual = source.lookup("NPM", "fixture", "1.0.0", stored.get(key));
        assertThat(actual.lookupFailed()).isEqualTo(stale);
        assertThat(actual.findings()).singleElement().extracting(v -> v.ghsaId()).isEqualTo("GHSA-fixture");
        assertThat(actual.findings().getFirst().fixVersion()).isEqualTo(stale ? null : "2.0.0");
        assertThat(source.lookup("NPM", "empty", "1.0.0", stored.get(empty)).lookupFailed()).isEqualTo(stale);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true", "7,true", "8,false", "40,false", "-1,false", "999,false"})
    void offlineOsvCoverageRequiresDatedRecentSource(int age, boolean resolved) {
        String emptyKey = AirgappedSnapshotService.componentKey("npm", "empty", "1.0.0");
        String affectedKey = AirgappedSnapshotService.componentKey("npm", "affected", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(emptyKey).payload("[]").build());
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(affectedKey)
                .payload("[{\"osvId\":\"GHSA-fixture\",\"fixVersion\":\"2.0.0\"}]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(2).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var actual = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "empty", "1.0.0"),
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "affected", "1.0.0")));
        assertThat(actual).allMatch(result -> result.resolved() == resolved);
        assertThat(actual.getFirst().vulns()).isEmpty();
        assertThat(actual.getLast().vulns()).singleElement().extracting(v -> v.osvId()).isEqualTo("GHSA-fixture");
        assertThat(actual.getLast().vulns().getFirst().fixVersion()).isEqualTo(resolved ? "2.0.0" : null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void storedFutureSourceDatesCannotReportHealthyFreshness(boolean withValidSource) {
        var today = java.time.LocalDate.now();
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(today.plusDays(10)).build());
        if (withValidSource) metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder()
                .source("epss").recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(today).build());
        assertThat(service.oldestSourceAsOf()).isNull();
        var indicator = new com.salkcoding.oswl.health.SnapshotFreshnessHealthIndicator(service);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "airgappedEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "stalenessWarnDays", 7);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "stalenessCriticalDays", 30);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "false", "{}", "[null]", "[1]", "[\"\"]"})
    void malformedFixConflictsRollBackTheImport(String candidates) throws Exception {
        String record = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\",\"vulns\":[" +
                "{\"osvId\":\"GHSA-fixture\",\"fixVersionConflictCandidates\":" + candidates + "}]}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", record)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsEntry("CVE-OLD", 0.25);
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        entries.save(SnapshotEntry.builder().source("osv").entryKey(key).payload(
                "[{\"osvId\":\"GHSA-fixture\",\"fixVersionConflictCandidates\":" + candidates + "}]").build());
        assertThat(service.findOsvVulns(List.of(key))).doesNotContainKey(key);
    }
    @Autowired AirgappedSnapshotService service;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired DataSource dataSource;

    @BeforeEach void existingSource() {
        entries.deleteAllInBatch();
        metadata.deleteAllInBatch();
        entries.save(SnapshotEntry.builder().source("epss").entryKey("CVE-OLD").payload("0.25").build());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "false", "{}", "[null]", "[1]", "[{}]"})
    void malformedVulnerabilityListsCannotReplaceExistingFindings(String vulns) throws Exception {
        for (String source : List.of("osv", "github-advisory", "nvd")) {
            String key = "NPM|fixture|1.0.0";
            String old = "[{\"osvId\":\"GHSA-old\",\"fixVersion\":\"2.0.0\"}]";
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey(key).payload(old).build());
            String line = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\",\"vulns\":" + vulns + "}";
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of(source + ".jsonl", line)))))
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(entries.findBySourceAndEntryKeyIn(source, List.of(key))).singleElement()
                    .extracting(SnapshotEntry::getPayload).isEqualTo(old);
        }
    }

    @Test void missingVulnsIsRejectedButExplicitEmptyAndUnresolvedRecordsRemainValid() throws Exception {
        String identity = "\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", "{" + identity + "}")))))
                .isInstanceOf(InvalidRequestException.class);
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", "{" + identity + ",\"vulns\":[]}",
                "unresolved.jsonl", "{" + identity + "}"))));
        assertThat(service.findOsvVulns(List.of("NPM|fixture|1.0.0"))).containsEntry("NPM|fixture|1.0.0", List.of());
        assertThat(service.findUnresolvedKeys(List.of("NPM|fixture|1.0.0"))).containsExactly("NPM|fixture|1.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "[null]", "[{}]", "false", "{}"})
    void corruptStoredVulnerabilitiesRemainUnresolved(String payload) {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(2).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.now()).build());
        String key = "NPM|fixture|1.0.0";
        for (String source : List.of("osv", "github-advisory", "nvd")) {
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey(key).payload(payload).build());
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey("NPM|clean|1.0.0").payload("[]").build());
        }
        var keys = List.of(key, "NPM|clean|1.0.0");
        assertThat(service.findOsvVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        assertThat(service.findGitHubAdvisoryVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        assertThat(service.findNvdVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        var results = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "fixture", "1.0.0"),
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "clean", "1.0.0")));
        assertThat(results.getFirst().resolved()).isFalse();
        assertThat(results.get(1).resolved()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"broken", "null", "[]", "{\"formatVersion\":3}",
            "{\"formatVersion\":0}", "{\"formatVersion\":null}", "{\"formatVersion\":\"2\"}",
            "{\"formatVersion\":2.5}", "{\"formatVersion\":2}",
            "{\"formatVersion\":2,\"files\":{}}", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{}}}"})
    void invalidMetadataCannotDowngradeOrBypassIntegrity(String meta) throws Exception {
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of(
                "meta.json", meta, "epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}")))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-declared", "unknown-entry", "nested-entry"})
    void versionTwoFileInventoryMustMatchTheArchive(String mismatch) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String manifestFiles = "\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}";
        if (mismatch.equals("missing-declared")) manifestFiles += ",\"osv.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}";
        var files = new LinkedHashMap<String, String>();
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{" + manifestFiles + "}}");
        files.put(mismatch.equals("nested-entry") ? "nested/epss.jsonl" : "epss.jsonl", line);
        if (mismatch.equals("unknown-entry")) files.put("typo-osv.jsonl", line);
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "-1", "1.5", "\"1\"", "2147483648", "0", "2"})
    void declaredLineCountMustBeValidAndMatchContent(String lines) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":" + lines + "}}}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {"2024-01-01;2026-01-01;MERGE;2024-01-01",
            "null;2026-01-01;MERGE;null", "2024-01-01;null;MERGE;null",
            "2026-01-01;2024-01-01;MERGE;2024-01-01", "2024-01-01;2026-01-01;REPLACE;2026-01-01"}, delimiter = ';', nullValues = "null")
    void mergingNewDataCannotRefreshRetainedOlderRecords(String oldDate, String newDate, String mode, String expected) throws Exception {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(oldDate == null ? null : java.time.LocalDate.parse(oldDate)).build());
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String source = newDate == null ? "{}" : "{\"asOf\":\"" + newDate + "\"}";
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":" + source
                + "},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line))),
                AirgappedSnapshotService.ImportMode.valueOf(mode));
        assertThat(metadata.findById("epss").orElseThrow().getSourceAsOf())
                .isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-NEW"))).hasSize(mode.equals("MERGE") ? 2 : 1);
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(0).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.of(2026, 1, 1)).build());
        assertThat(service.oldestSourceAsOf()).isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"not-a-date\"", "\"2026-02-30\"", "\"2099-01-01\"", "123", "{}", "\"\""})
    void invalidSourceDatesCannotEstablishFreshness(String date) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":{\"asOf\":" + date
                + "}},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "\"0.5\"", "-0.01", "1.01", "1e999", "{}"})
    void invalidEpssScoresDoNotReplaceExistingData(String score) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":" + score + "}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.01", "1.01"})
    void invalidStoredEpssScoresAreNotReturned(String score) {
        entries.saveAndFlush(SnapshotEntry.builder().source("epss").entryKey("CVE-INVALID").payload(score).build());
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-INVALID"))).containsOnly(entry("CVE-OLD", 0.25));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0, 0.5, 1})
    void validEpssProbabilitiesRoundTrip(double score) throws Exception {
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",
                "{\"cveId\":\"CVE-NEW\",\"score\":" + score + "}"))));
        assertThat(service.findEpssScores(List.of("CVE-NEW"))).containsOnly(entry("CVE-NEW", score));
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
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"wrong\",\"lines\":1}}}");
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
