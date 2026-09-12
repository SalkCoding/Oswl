package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta;
import com.salkcoding.oswl.repository.snapshot.SnapshotEntryRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(properties = {
        "spring.datasource.url=${OSWL_SNAPSHOT_READ_URL:jdbc:h2:mem:snapshot-read-consistency;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT}",
        "spring.datasource.driver-class-name=${OSWL_SNAPSHOT_READ_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${OSWL_SNAPSHOT_READ_USER:sa}",
        "spring.datasource.password=${OSWL_SNAPSHOT_READ_PASSWORD:}",
        "spring.jpa.database-platform=${OSWL_SNAPSHOT_READ_DIALECT:org.hibernate.dialect.H2Dialect}"})
class SnapshotReadConsistencyTest {
    @MockitoSpyBean AirgappedSnapshotService snapshots;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @ParameterizedTest
    @CsvSource({"github-advisory,true,false", "github-advisory,false,true", "github-advisory,true,true",
            "nvd,true,false", "nvd,false,true", "nvd,true,true"})
    void otherSourcesCannotBorrowCoverageFromAConcurrentPublication(String source, boolean stale, boolean unresolved) throws Exception {
        String name = "other-read-" + java.util.UUID.randomUUID();
        String key = AirgappedSnapshotService.componentKey("npm", name, "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey(key).payload("[]").build());
        if (unresolved) entries.saveAndFlush(SnapshotEntry.builder().source("unresolved").entryKey(key).payload("{}").build());
        metadata.saveAndFlush(SnapshotMeta.builder().source(source).recordCount(1).importedAt(LocalDateTime.now())
                .sourceAsOf(LocalDate.now().minusDays(stale ? 30 : 0)).build());
        AtomicBoolean publishOnce = new AtomicBoolean();
        try (var writer = Executors.newSingleThreadExecutor()) {
            org.mockito.stubbing.Answer<Object> publish = call -> {
                Object rows = call.callRealMethod();
                if (publishOnce.compareAndSet(false, true)) {
                    writer.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        jdbc.update("UPDATE airgapped_snapshot_entries SET payload=? WHERE source=? AND entry_key=?",
                                "[{\"osvId\":\"GHSA-new\",\"cveId\":\"CVE-2026-1234\",\"fixVersion\":\"2.0.0\"}]", source, key);
                        jdbc.update("DELETE FROM airgapped_snapshot_entries WHERE source='unresolved' AND entry_key=?", key);
                        jdbc.update("UPDATE airgapped_snapshot_meta SET source_as_of=? WHERE source=?", LocalDate.now(), source);
                    })).get(10, TimeUnit.SECONDS);
                }
                return rows;
            };
            if (source.equals("github-advisory")) doAnswer(publish).when(snapshots).findGitHubAdvisoryVulns(anyCollection());
            else doAnswer(publish).when(snapshots).findNvdVulns(anyCollection());
            var github = new com.salkcoding.oswl.client.GitHubAdvisoryClient(snapshots, true, null,
                    "https://api.github.com/graphql", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
            var nvd = new com.salkcoding.oswl.client.NvdClient(snapshots, true, null,
                    java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
            var old = source.equals("github-advisory")
                    ? new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(github)
                        .lookupSnapshot("npm", name, "1.0.0", github.findSnapshotByComponentKeys(List.of(key)).get(key))
                    : new com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource(nvd, null)
                        .lookupSnapshot(name, "1.0.0", null, nvd.findSnapshotByComponentKeys(List.of(key)).get(key));
            assertThat(publishOnce).isTrue();
            assertThat(old.findings()).isEmpty();
            assertThat(old.lookupFailed()).isTrue();
            var current = source.equals("github-advisory")
                    ? new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(github)
                        .lookupSnapshot("npm", name, "1.0.0", github.findSnapshotByComponentKeys(List.of(key)).get(key))
                    : new com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource(nvd, null)
                        .lookupSnapshot(name, "1.0.0", null, nvd.findSnapshotByComponentKeys(List.of(key)).get(key));
            assertThat(current.findings()).hasSize(1);
            assertThat(current.lookupFailed()).isFalse();
            assertThat(current.queried()).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"true,false,false", "false,true,false", "true,true,false", "true,false,true", "false,true,true", "true,true,true"})
    void concurrentPublicationCannotGiveOldEmptyFindingsNewCoverage(boolean stale, boolean unresolved, boolean outerTransaction) throws Exception {
        if (System.getenv("OSWL_SNAPSHOT_READ_URL") != null) {
            try (var connection = jdbc.getDataSource().getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            }
        }
        String name = "read-consistency-" + java.util.UUID.randomUUID();
        String key = AirgappedSnapshotService.componentKey("npm", name, "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(key).payload("[]").build());
        if (unresolved) entries.saveAndFlush(SnapshotEntry.builder().source("unresolved").entryKey(key).payload("{}").build());
        metadata.saveAndFlush(SnapshotMeta.builder().source("osv").recordCount(1).importedAt(LocalDateTime.now())
                .sourceAsOf(LocalDate.now().minusDays(stale ? 30 : 0)).build());
        AtomicBoolean publishOnce = new AtomicBoolean();
        try (var writer = Executors.newSingleThreadExecutor()) {
            doAnswer(call -> {
                Object rows = call.callRealMethod();
                if (publishOnce.compareAndSet(false, true)) {
                    writer.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        jdbc.update("UPDATE airgapped_snapshot_entries SET payload=? WHERE source='osv' AND entry_key=?",
                                "[{\"osvId\":\"OSV-new\",\"fixVersion\":\"2.0.0\"}]", key);
                        jdbc.update("DELETE FROM airgapped_snapshot_entries WHERE source='unresolved' AND entry_key=?", key);
                        jdbc.update("UPDATE airgapped_snapshot_meta SET source_as_of=? WHERE source='osv'", LocalDate.now());
                    })).get(10, TimeUnit.SECONDS);
                }
                return rows;
            }).when(snapshots).findOsvVulns(anyCollection());
            var client = new OsvClient(snapshots, true);
            var query = new OsvClient.OsvQuery("npm", name, "1.0.0");
            var outer = new TransactionTemplate(transactions);
            outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            var old = outerTransaction ? outer.execute(status -> client.queryBatch(List.of(query)).getFirst())
                    : client.queryBatch(List.of(query)).getFirst();
            assertThat(publishOnce).isTrue();
            assertThat(old.vulns()).isEmpty();
            assertThat(old.resolved()).isFalse();
            assertThat(old.validUntil()).isEqualTo(LocalDate.now().minusDays(stale ? 30 : 0)
                    .plusDays(8).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            var updated = client.queryBatch(List.of(query)).getFirst();
            assertThat(updated.resolved()).isTrue();
            assertThat(updated.validUntil()).isEqualTo(LocalDate.now().plusDays(8)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            assertThat(updated.vulns()).singleElement().extracting(OsvClient.OsvVuln::osvId).isEqualTo("OSV-new");
        }
    }
}
