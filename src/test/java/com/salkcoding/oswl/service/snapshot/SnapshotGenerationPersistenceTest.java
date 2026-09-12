package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta;
import com.salkcoding.oswl.repository.snapshot.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=${OSWL_SNAPSHOT_READ_URL:jdbc:h2:mem:snapshot-generation;DB_CLOSE_DELAY=0;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT}",
        "spring.datasource.driver-class-name=${OSWL_SNAPSHOT_READ_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${OSWL_SNAPSHOT_READ_USER:sa}",
        "spring.datasource.password=${OSWL_SNAPSHOT_READ_PASSWORD:}",
        "spring.jpa.database-platform=${OSWL_SNAPSHOT_READ_DIALECT:org.hibernate.dialect.H2Dialect}"})
class SnapshotGenerationPersistenceTest {
    @Autowired AirgappedSnapshotService snapshots;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired SnapshotGenerationRepository generations;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.salkcoding.oswl.repository.scan.ScanResultRepository scans;
    @Autowired com.salkcoding.oswl.repository.project.ProjectRepository projects;
    @Autowired com.salkcoding.oswl.service.scan.ScanAssessmentService assessments;
    @Autowired com.salkcoding.oswl.service.scan.ScanSummaryReader summaryReader;
    @Autowired com.salkcoding.oswl.service.reporting.ComplianceReportService reports;
    @MockitoSpyBean SnapshotGenerationService publication;

    @org.junit.jupiter.api.BeforeEach void isolatedStore() {
        scans.deleteAllInBatch();
        projects.deleteAllInBatch();
        jdbc.update("DELETE FROM snapshot_active_generation");
        jdbc.update("DELETE FROM snapshot_generation_entries");
        jdbc.update("DELETE FROM snapshot_generations");
        entries.deleteAllInBatch();
        metadata.deleteAllInBatch();
    }

    @Test void preservedAssessmentSurvivesChangedLibraryDataAndStaleScanSaves() {
        var project = projects.saveAndFlush(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Assessment history").build());
        var scan = scans.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("main").status(com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED).build());
        var stale = scans.findById(scan.getId()).orElseThrow();
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder().id(31L).name("fixture")
                .version("1").ecosystem("NPM").licenseStatus(com.salkcoding.oswl.domain.enums.LicenseStatus.RESTRICTED).build();
        library.getCves().add(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .cveId("CVE-2026-123450").severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH)
                .fixVersion("2").kevListed(true).build());
        var component = com.salkcoding.oswl.domain.entity.scan.ScanComponent.builder().library(library).scanResult(scan).build();
        assessments.capture(scan.getId(), List.of(component));
        String original = scans.findById(scan.getId()).orElseThrow().getAssessmentJson();
        assertThat(original).isNotBlank();
        library.getCves().clear();
        library.updateLicense("MIT",List.of("MIT"),com.salkcoding.oswl.domain.enums.LicenseStatus.PERMITTED);
        assessments.capture(scan.getId(),List.of(component));
        scans.saveAndFlush(stale);
        var preserved = scans.findById(scan.getId()).orElseThrow();
        assertThat(preserved.getAssessmentJson()).isEqualTo(original);
        var summary = summaryReader.read(List.of(preserved)).get(scan.getId());
        assertThat(summary.security()).containsExactly(0,1,0,0,0);
        assertThat(summary.licenses()).containsExactly(1,0,0,0);
        var report = reports.build(project.getId());
        assertThat(report.highCves()).isEqualTo(1);
        assertThat(report.licenseViolations()).isEqualTo(1);
        assertThat(report.kevRows()).singleElement().satisfies(row -> {
            assertThat(row.fixVersion()).isEqualTo("2");
            assertThat(row.cveId()).isEqualTo("CVE-2026-123450");
        });
        assertThatThrownBy(() -> com.salkcoding.oswl.service.scan.ScanAssessmentService.read("invalid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void concurrentScanPinningAndStaleSavesCannotChangeTheChosenGeneration() throws Exception {
        snapshots.importBundle(new ByteArrayInputStream(bundle("CVE-2026-123450", "0.1")));
        long first = generations.activeId();
        snapshots.importBundle(new ByteArrayInputStream(bundle("CVE-2026-123450", "0.2")));
        long second = generations.activeId();
        var project = projects.saveAndFlush(com.salkcoding.oswl.domain.entity.project.Project.builder().name("generation fixture").build());
        var scan = scans.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).build());
        var stale = scans.findById(scan.getId()).orElseThrow();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); return scans.pinSnapshotGenerationIfAbsent(scan.getId(), first); });
            var b = workers.submit(() -> { start.await(); return scans.pinSnapshotGenerationIfAbsent(scan.getId(), second); });
            start.countDown();
            assertThat(a.get(10, java.util.concurrent.TimeUnit.SECONDS) + b.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
        long selected = scans.findSnapshotGenerationId(scan.getId());
        assertThat(selected).isIn(first, second);
        assertThat(scans.pinSnapshotGenerationIfAbsent(scan.getId(), selected == first ? second : first)).isZero();
        stale.startAnalyzing();
        scans.saveAndFlush(stale);
        var persisted = scans.findById(scan.getId()).orElseThrow();
        assertThat(persisted.getSnapshotGenerationId()).isEqualTo(selected);
        assertThatThrownBy(() -> persisted.pinSnapshotGeneration(selected == first ? second : first))
                .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true"})
    void pinnedProvidersKeepTheirRowsAndDatesAcrossPublication(boolean stale, boolean unresolved) throws Exception {
        String name = "generation-provider-fixture";
        String key = AirgappedSnapshotService.componentKey("npm", name, "1.0.0");
        snapshots.importBundle(new ByteArrayInputStream(providerBundle(name, "old", stale ? 30 : 0, unresolved)));
        long old = generations.activeId();
        var osv = new com.salkcoding.oswl.client.OsvClient(snapshots, true);
        var deps = new com.salkcoding.oswl.client.DepsDevClient(snapshots, true);
        var github = new com.salkcoding.oswl.client.GitHubAdvisoryClient(snapshots, true, null, null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        var nvd = new com.salkcoding.oswl.client.NvdClient(snapshots, true, null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        var epss = new com.salkcoding.oswl.client.EpssClient(snapshots, true);
        var kev = new com.salkcoding.oswl.client.KevCatalogService(snapshots, true);
        try (var scope = publication.open(old)) {
            try (var writer = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                writer.submit(() -> {
                    snapshots.importBundle(new ByteArrayInputStream(providerBundle(name, "new", 0, false)));
                    kev.refresh();
                    return null;
                }).get(20, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(generations.activeId()).isNotEqualTo(old);
            assertThat(osv.queryBatch(List.of(new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", name, "1.0.0")))
                    .getFirst().vulns()).singleElement().extracting(v -> v.osvId()).isEqualTo("OSV-old");
            var version = deps.getVersionsBatch(List.of(new com.salkcoding.oswl.client.DepsDevClient.ComponentKey("NPM", name, "1.0.0"))).getFirst();
            assertThat(version.advisoryKeys()).containsExactly("GHSA-old");
            assertThat(version.resolved()).isEqualTo(!stale && !unresolved);
            var advisory = deps.getAdvisoriesBatch(List.of("GHSA-old")).getFirst();
            assertThat(advisory.title()).isEqualTo("old");
            assertThat(advisory.current()).isEqualTo(!stale);
            var gh = github.findSnapshotByComponentKeys(List.of(key)).get(key);
            assertThat(gh.findings()).singleElement().extracting(v -> v.ghsaId()).isEqualTo("GHSA-old");
            assertThat(gh.complete()).isEqualTo(!stale && !unresolved);
            var nv = nvd.findSnapshotByComponentKeys(List.of(key)).get(key);
            assertThat(nv.findings()).singleElement().extracting(v -> v.cveId()).isEqualTo("CVE-2026-234560");
            assertThat(nv.complete()).isEqualTo(!stale && !unresolved);
            assertThat(epss.fetchScores(List.of("CVE-2026-234560")))
                    .isEqualTo(stale ? Map.of() : Map.of("CVE-2026-234560", 0.1));
            assertThat(kev.listingStatus("CVE-2026-234560")).isTrue();
            assertThat(kev.listingStatus("CVE-2026-234561")).isEqualTo(stale ? null : Boolean.FALSE);
            try (var nested = publication.open(generations.activeId())) {
                assertThat(kev.listingStatus("CVE-2026-234561")).isTrue();
                assertThat(github.findSnapshotByComponentKeys(List.of(key)).get(key).findings())
                        .singleElement().extracting(v -> v.ghsaId()).isEqualTo("GHSA-new");
            }
            assertThat(SnapshotGenerationScope.current().generationId()).isEqualTo(old);
        }
        assertThat(SnapshotGenerationScope.current()).isNull();
        assertThat(kev.listingStatus("CVE-2026-234561")).isTrue();
        assertThatThrownBy(() -> publication.open(Long.MAX_VALUE)).isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
        assertThat(SnapshotGenerationScope.current()).isNull();
    }

    private static byte[] providerBundle(String name, String label, int age, boolean unresolved) throws Exception {
        String cve = label.equals("old") ? "CVE-2026-234560" : "CVE-2026-234561";
        String prefix = "{\"ecosystem\":\"npm\",\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"vulns\":[";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("unresolved.jsonl", unresolved ? "{\"ecosystem\":\"npm\",\"name\":\"" + name + "\",\"version\":\"1.0.0\"}\n" : "");
        files.put("osv.jsonl", prefix + "{\"osvId\":\"OSV-" + label + "\",\"cveId\":\"" + cve + "\"}]}\n");
        files.put("github-advisory.jsonl", prefix + "{\"osvId\":\"GHSA-" + label + "\",\"cveId\":\"" + cve + "\"}]}\n");
        files.put("nvd.jsonl", prefix + "{\"cveId\":\"" + cve + "\"}]}\n");
        files.put("depsdev.jsonl", "{\"type\":\"version\",\"ecosystem\":\"npm\",\"name\":\"" + name + "\",\"version\":\"1.0.0\",\"licenses\":[],\"advisoryKeys\":[\"GHSA-" + label + "\"]}\n"
                + "{\"type\":\"advisory\",\"ghsaId\":\"GHSA-" + label + "\",\"title\":\"" + label + "\",\"aliases\":[]}\n");
        files.put("epss.jsonl", "{\"cveId\":\"" + cve + "\",\"score\":0.1}\n");
        files.put("kev.jsonl", "{\"cveId\":\"" + cve + "\"}\n");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var meta = mapper.createObjectNode().put("formatVersion", 2);
        var sources = meta.putObject("sources");
        for (String source : List.of("osv", "github-advisory", "nvd", "depsdev-version", "depsdev-advisory", "epss", "kev"))
            sources.putObject(source).put("asOf", LocalDate.now().minusDays(age).toString());
        var manifest = meta.putObject("files");
        for (var entry : files.entrySet()) manifest.putObject(entry.getKey()).put("lines", entry.getValue().lines().count())
                .put("sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue().getBytes(StandardCharsets.UTF_8))));
        files.put("meta.json", meta.toString());
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    @Test void legacyReplaceMergeAndRollbackPreserveIndependentGenerations() throws Exception {
        if (System.getenv("OSWL_SNAPSHOT_READ_URL") != null) {
            try (var connection = jdbc.getDataSource().getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            }
        }
        String first = "CVE-2026-123456", second = "CVE-2026-123457";
        String notices = "[{\"credit\":\"자체 검증 저작자\",\"license\":\"fixture only\"}]";
        entries.saveAndFlush(SnapshotEntry.builder().source("epss").entryKey(first).payload("0.1").build());
        metadata.saveAndFlush(SnapshotMeta.builder().source("epss").recordCount(1).importedAt(LocalDateTime.now())
                .sourceAsOf(LocalDate.now().minusDays(30)).dataNotices(notices).build());
        assertThat(generations.activeId()).isNull();
        snapshots.importBundle(new ByteArrayInputStream(bundle(first, "0.5")), AirgappedSnapshotService.ImportMode.REPLACE);
        long baseline = jdbc.queryForObject("SELECT MIN(id) FROM snapshot_generations", Long.class);
        long replaced = generations.activeId();
        assertThat(replaced).isNotEqualTo(baseline);
        assertThat(generations.payloads(baseline, "epss", List.of(first))).containsExactlyEntriesOf(Map.of(first, "0.1"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = json.readTree(generations.metadata(baseline)).path("epss");
        assertThat(original.path("sourceAsOf").asText()).isEqualTo(LocalDate.now().minusDays(30).toString());
        assertThat(original.path("dataNotices").asText()).isEqualTo(notices);
        assertThat(generations.payloads(replaced, "epss", List.of(first))).containsExactlyEntriesOf(Map.of(first, "0.5"));
        assertThat(json.readTree(generations.metadata(replaced)).path("epss").path("sourceAsOf").isNull()).isTrue();

        snapshots.importBundle(new ByteArrayInputStream(bundle(second, "0.8")), AirgappedSnapshotService.ImportMode.MERGE);
        long merged = generations.activeId();
        assertThat(merged).isNotEqualTo(replaced);
        assertThat(generations.payloads(merged, "epss", List.of(first, second)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, "0.5", second, "0.8"));
        assertThat(generations.payloads(replaced, "epss", List.of(second))).isEmpty();

        long before = jdbc.queryForObject("SELECT COUNT(*) FROM snapshot_generations", Long.class);
        assertThatThrownBy(() -> snapshots.importBundle(new ByteArrayInputStream(bundle(first, "NaN"))))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        assertThat(generations.activeId()).isEqualTo(merged);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshot_generations", Long.class)).isEqualTo(before);

        var failedId = new java.util.concurrent.atomic.AtomicLong();
        SnapshotGenerationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(publication);
        doAnswer(call -> {
            failedId.set((Long) call.callRealMethod());
            throw new com.salkcoding.oswl.exception.InvalidRequestException("failure after generation copy");
        }).when(target).publish();
        assertThatThrownBy(() -> snapshots.importBundle(new ByteArrayInputStream(bundle(first, "0.9"))))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
        assertThat(failedId.get()).isPositive();
        assertThat(generations.activeId()).isEqualTo(merged);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshot_generations", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshot_generation_entries WHERE generation_id=?", Long.class, failedId.get())).isZero();
        assertThat(snapshots.findEpssScores(List.of(first, second))).containsExactlyInAnyOrderEntriesOf(Map.of(first, 0.5, second, 0.8));
        reset(target);
        snapshots.importBundle(new ByteArrayInputStream(bundle(first, "0.9")));
        assertThat(generations.payloads(merged, "epss", List.of(first, second)))
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, "0.5", second, "0.8"));
        assertThat(generations.payloads(generations.activeId(), "epss", List.of(first, second)))
                .containsExactlyEntriesOf(Map.of(first, "0.9"));
    }

    @Test void nvdApplicabilitySurvivesOfflineSerializationAndImport() throws Exception {
        String raw = "{\"id\":\"CVE-2026-123450\",\"sourceIdentifier\":\"fixture\",\"lastModified\":\"2026-09-12T00:00:00.000\",\"configurations\":[{\"operator\":\"AND\",\"nodes\":[{\"operator\":\"OR\",\"cpeMatch\":[{\"vulnerable\":false,\"criteria\":\"fixture-os\"}]}]}]}";
        var finding = com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().cveId("CVE-2026-123450")
                .nvdApplicability(raw).matchConfidence(com.salkcoding.oswl.domain.enums.MatchConfidence.HIGH).build();
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder().name("fixture").version("1").ecosystem("CONAN").build();
        var line = new StringBuilder();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(snapshots,"appendVulnLines",line,library,List.of(finding));
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.toString()).path("vulns").get(0)
                .path("nvdApplicability").asText()).isEqualTo(raw);
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("nvd.jsonl"));
            zip.write(line.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        snapshots.importBundle(new ByteArrayInputStream(output.toByteArray()));
        var client = new com.salkcoding.oswl.client.NvdClient(snapshots,true,null,Duration.ofSeconds(1),Duration.ofSeconds(1));
        var restored = client.findSnapshotByComponentKeys(List.of("CONAN|fixture|1")).get("CONAN|fixture|1");
        assertThat(restored.findings()).singleElement().satisfies(c -> assertThat(c.nvdApplicability()).isEqualTo(raw));
        assertThat(restored.complete()).isFalse(); // Missing source date remains incomplete despite retained configurations.
    }

    @Test void migrationCreatesGenerationConstraintsWithoutChangingLegacyRows() throws Exception {
        String schema = "generation_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = jdbc.getDataSource().getConnection()) {
            String previous = connection.getSchema();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA \"" + schema + "\"");
                connection.setSchema(schema);
                statement.execute("CREATE TABLE airgapped_snapshot_entries(id BIGINT PRIMARY KEY, payload TEXT)");
                statement.execute("INSERT INTO airgapped_snapshot_entries VALUES (1, 'original')");
                statement.execute("CREATE TABLE library_cves(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO library_cves(id) VALUES (1)");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V44__nvd_applicability_evidence.sql"));
                try (var rows = statement.executeQuery("SELECT nvd_applicability FROM library_cves WHERE id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isNull();
                }
                statement.execute("CREATE TABLE scan_results(id BIGINT PRIMARY KEY, project_id BIGINT)");
                statement.execute("INSERT INTO scan_results(id) VALUES (1)");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V35__database_mutation_locks.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V40__snapshot_generations.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V41__scan_snapshot_generation.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V42__scan_retry_identity.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V43__scan_assessment_evidence.sql"));
                try (var rows = statement.executeQuery("SELECT assessment_json FROM scan_results WHERE id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isNull();
                }
                try (var rows = statement.executeQuery("SELECT idempotency_key,input_digest FROM scan_results WHERE id=1")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull(); assertThat(rows.getString(2)).isNull();
                }
                statement.execute("INSERT INTO scan_results(id,project_id) VALUES (2,1),(3,1)");
                statement.execute("INSERT INTO scan_results(id,project_id,idempotency_key) VALUES (4,1,'retry'),(5,2,'retry')");
                assertThatThrownBy(() -> statement.execute("INSERT INTO scan_results(id,project_id,idempotency_key) VALUES (6,1,'retry')"))
                        .isInstanceOf(java.sql.SQLException.class).extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("23505");
                try (var rows = statement.executeQuery("SELECT snapshot_generation_id FROM scan_results WHERE id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
                }
                assertThatThrownBy(() -> statement.execute("UPDATE scan_results SET snapshot_generation_id=999 WHERE id=1"))
                        .isInstanceOf(java.sql.SQLException.class).extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo(connection.getMetaData().getDatabaseProductName().equals("PostgreSQL") ? "23503" : "23506");
                statement.execute("INSERT INTO snapshot_generations(published_at, source_metadata) VALUES (CURRENT_TIMESTAMP, '{}')");
                long generation;
                try (var rows = statement.executeQuery("SELECT id FROM snapshot_generations")) {
                    assertThat(rows.next()).isTrue(); generation = rows.getLong(1);
                }
                statement.execute("INSERT INTO snapshot_active_generation(id,generation_id) VALUES (1," + generation + ")");
                statement.execute("INSERT INTO snapshot_generation_entries(generation_id,source,entry_key,payload) VALUES (" + generation + ",'osv','fixture','원문')");
                assertThatThrownBy(() -> statement.execute("INSERT INTO snapshot_generation_entries(generation_id,source,entry_key,payload) VALUES (" + generation + ",'osv','fixture','duplicate')"))
                        .isInstanceOf(java.sql.SQLException.class).extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("23505");
                assertThatThrownBy(() -> statement.execute("DELETE FROM snapshot_generations WHERE id=" + generation))
                        .isInstanceOf(java.sql.SQLException.class).extracting(error -> ((java.sql.SQLException) error).getSQLState()).isEqualTo("23503");
                try (var rows = statement.executeQuery("SELECT payload FROM airgapped_snapshot_entries WHERE id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("original");
                }
                try (var rows = statement.executeQuery("SELECT payload FROM snapshot_generation_entries")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("원문");
                }
            } finally {
                connection.setSchema(previous);
                try (var statement = connection.createStatement()) { statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE"); }
            }
        }
    }

    private static byte[] bundle(String id, String score) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("epss.jsonl"));
            zip.write(("{\"cveId\":\"" + id + "\",\"score\":" + (score.equals("NaN") ? "\"NaN\"" : score) + "}\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
