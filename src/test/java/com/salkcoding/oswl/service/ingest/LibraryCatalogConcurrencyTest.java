package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.repository.vulnerability.LibraryCatalogRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LibraryCatalogConcurrencyTest {
    @Autowired LibraryCatalogRepository catalog;
    @Autowired LibraryRepository libraries;
    @Autowired PlatformTransactionManager transactions;

    @Test void concurrentFirstInsertReusesTheWinnerAndLeavesBothTransactionsUsable() throws Exception {
        String name = "concurrent-" + UUID.randomUUID();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<Long> ingest = () -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(libraries.findByName(name)).isEmpty();
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
                catalog.ensurePresent(List.of(Library.builder().name(name).version("1").ecosystem("NPM").build()));
                var winner = libraries.findByNameAndVersionAndEcosystem(name, "1", "NPM").orElseThrow();
                // A follow-up write and commit would fail if the losing INSERT had poisoned the transaction.
                winner.updateLicense("MIT", List.of("MIT"), com.salkcoding.oswl.domain.enums.LicenseStatus.PERMITTED);
                libraries.saveAndFlush(winner);
                return winner.getId();
            });
            var first = workers.submit(ingest);
            var second = workers.submit(ingest);
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        } finally {
            libraries.deleteAll(libraries.findByName(name));
        }
    }
}
