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
    @MockitoSpyBean SnapshotGenerationService publication;

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

    @Test void migrationCreatesGenerationConstraintsWithoutChangingLegacyRows() throws Exception {
        String schema = "generation_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = jdbc.getDataSource().getConnection()) {
            String previous = connection.getSchema();
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA \"" + schema + "\"");
                connection.setSchema(schema);
                statement.execute("CREATE TABLE airgapped_snapshot_entries(id BIGINT PRIMARY KEY, payload TEXT)");
                statement.execute("INSERT INTO airgapped_snapshot_entries VALUES (1, 'original')");
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V35__database_mutation_locks.sql"));
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                        new org.springframework.core.io.ClassPathResource("db/migration/V40__snapshot_generations.sql"));
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
