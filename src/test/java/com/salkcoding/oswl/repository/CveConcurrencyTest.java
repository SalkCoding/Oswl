package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@RequiredArgsConstructor
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class CveConcurrencyTest {
    private final CveRepository cves;
    private final LibraryRepository libraries;
    private final PlatformTransactionManager transactions;
    private final List<Long> ownedLibraries = new ArrayList<>();

    private Cve fixture() {
        var library = libraries.save(Library.builder().name("concurrency-" + UUID.randomUUID())
                .version("1").ecosystem("NPM").build());
        ownedLibraries.add(library.getId());
        return cves.save(Cve.builder().library(library).ghsaId("OSV-fixture")
                .fixVersion("2.0.0").osvRevisionEvidence("prior").build());
    }

    @AfterEach
    void cleanup() {
        ownedLibraries.forEach(libraries::deleteById);
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "saveAll", "saveAndFlush", "saveAllAndFlush", "cascade"})
    void staleDetachedWriterCannotOverwriteAcceptedEvidence(String method) {
        var original = fixture();
        var staleLibrary = new TransactionTemplate(transactions).execute(status -> {
            var library = libraries.findById(original.getLibrary().getId()).orElseThrow();
            library.getCves().size();
            return library;
        });
        var stale = staleLibrary.getCves().getFirst();
        var fresh = cves.findById(original.getId()).orElseThrow();
        fresh.setOsvRevisionEvidence("newer accepted revision");
        fresh.refreshFixVersionFromAdvisory("OSV-fixture", "3.0.0");
        cves.save(fresh);
        stale.setOsvRevisionEvidence("stale revision");

        assertThatThrownBy(() -> {
            switch (method) {
                case "save" -> cves.save(stale);
                case "saveAll" -> cves.saveAll(List.of(stale));
                case "saveAndFlush" -> cves.saveAndFlush(stale);
                case "saveAllAndFlush" -> cves.saveAllAndFlush(List.of(stale));
                case "cascade" -> libraries.save(staleLibrary);
                default -> throw new AssertionError(method);
            }
        }).isInstanceOf(OptimisticLockingFailureException.class);
        var retained = cves.findById(original.getId()).orElseThrow();
        assertThat(retained.getOsvRevisionEvidence()).isEqualTo("newer accepted revision");
        assertThat(retained.getFixVersion()).isEqualTo("3.0.0");
    }

    @Test
    void sameDetachedFindingSupportsSequentialSourceAndThreatIntelWrites() {
        var finding = fixture();
        finding.setOsvRevisionEvidence("first");
        cves.save(finding);
        finding.setNvdApplicability("second");
        cves.save(finding);
        finding.setThreatIntel(0.5, true);
        cves.saveAll(List.of(finding));
        finding.setOsvRevisionEvidence("third");
        cves.saveAll(List.of(finding));
        var retained = cves.findById(finding.getId()).orElseThrow();
        assertThat(retained.getOsvRevisionEvidence()).isEqualTo("third");
        assertThat(retained.getNvdApplicability()).isEqualTo("second");
        assertThat(retained.getEpssScore()).isEqualTo(0.5);
        assertThat(finding.getRowVersion()).isEqualTo(retained.getRowVersion()).isGreaterThan(0L);
    }

    @Test
    void batchConflictRollsBackOtherFindingsWithoutAcknowledgingTheirVersions() {
        var first = fixture();
        var stale = fixture();
        var newer = cves.findById(stale.getId()).orElseThrow();
        newer.setOsvRevisionEvidence("newer");
        cves.save(newer);
        Long firstVersion = first.getRowVersion();
        first.setOsvRevisionEvidence("must roll back");
        stale.setOsvRevisionEvidence("stale");
        assertThatThrownBy(() -> cves.saveAll(List.of(first, stale)))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(cves.findById(first.getId()).orElseThrow().getOsvRevisionEvidence()).isEqualTo("prior");
        assertThat(first.getRowVersion()).isEqualTo(firstVersion);
        assertThat(cves.findById(stale.getId()).orElseThrow().getOsvRevisionEvidence()).isEqualTo("newer");
    }

    @Test
    void libraryCascadeBetweenSourceWritesDoesNotCauseFalseConflict() {
        var original = fixture();
        var library = new TransactionTemplate(transactions).execute(status -> {
            var loaded = libraries.findById(original.getLibrary().getId()).orElseThrow();
            loaded.getCves().size();
            return loaded;
        });
        var finding = library.getCves().getFirst();
        finding.setOsvRevisionEvidence("osv");
        cves.save(finding);
        library.markFetched();
        libraries.save(library);
        finding.setNvdApplicability("nvd");
        cves.save(finding);
        var retained = cves.findById(finding.getId()).orElseThrow();
        assertThat(retained.getOsvRevisionEvidence()).isEqualTo("osv");
        assertThat(retained.getNvdApplicability()).isEqualTo("nvd");
    }
}
