package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus.Phase;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.repository.scan.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class ImportJobStoreTest {
    @Autowired ImportJobStore store;
    @Autowired ImportJobRepository jobs;
    @Autowired ImportCoordinatorRepository coordinator;
    @Autowired ScanResultRepository scans;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private QuickImportJobStatus status() {
        return QuickImportJobStatus.builder().jobId(UUID.randomUUID().toString()).phase(Phase.QUEUED)
                .repoLabel("example/repo").apiToken("never-store-this-token").build();
    }
    private long owner() { return Math.abs(UUID.randomUUID().getMostSignificantBits()); }
    private ImportJobStore secondWorker() { return new ImportJobStore(jobs, coordinator, scans, jdbc); }
    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> action.run());
    }

    @Test void parallelAdmissionHonorsCapAndDuplicateReservation() throws Exception {
        long owner = owner();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        var second = secondWorker();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> reserveAfter(ready, store, owner, accepted));
            var b = pool.submit(() -> reserveAfter(ready, second, owner, accepted));
            ready.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertThat(accepted).hasValue(1);
        assertThat(store.list(owner)).hasSize(1);
        assertThatThrownBy(() -> store.reserve(status(), owner, "repo", 10, false))
                .isInstanceOf(com.salkcoding.oswl.exception.QuickImportDuplicateException.class);
        store.list(owner).forEach(s -> store.cancel(s.getJobId(), owner));
    }
    private void reserveAfter(CountDownLatch ready, ImportJobStore worker, long owner, AtomicInteger accepted) {
        try {
            ready.await();
            inTransaction(() -> worker.reserve(status(), owner, "repo", 1, false));
            accepted.incrementAndGet();
        } catch (com.salkcoding.oswl.exception.QuickImportQueueFullException expected) {
            // The losing concurrent reservation must be rejected before it creates a job.
        } catch (InterruptedException e) { throw new RuntimeException(e); }
    }

    @Test void remoteOwnerLookupCancellationAndTerminalGuards() {
        var job = status(); long owner = owner();
        store.reserve(job, owner, job.getJobId(), 2, false);
        var second = secondWorker();
        inTransaction(() -> {
            assertThat(second.read(job.getJobId(), owner).getApiToken()).isNull();
            assertThat(second.read(job.getJobId(), owner + 1)).isNull();
            assertThat(second.cancel(job.getJobId(), owner)).isTrue();
        });
        store.publish(job.toBuilder().phase(Phase.DONE).build());
        assertThat(store.read(job.getJobId(), owner).getPhase()).isEqualTo(Phase.FAILED);
    }

    @Test void globalWorkerCapAndExpiredWorkerRecovery() {
        var first = status(); var next = status(); long owner = owner();
        var second = secondWorker();
        store.reserve(first, owner, first.getJobId(), 2, false);
        inTransaction(() -> second.reserve(next, owner, next.getJobId(), 2, false));
        assertThat(store.claim(first.getJobId(), 1)).isTrue();
        inTransaction(() -> assertThat(second.claim(next.getJobId(), 1)).isFalse());
        jdbc.update("UPDATE import_jobs SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE job_id = ?", first.getJobId());
        inTransaction(second::maintain);
        assertThat(store.read(first.getJobId(), owner).getPhase()).isEqualTo(Phase.FAILED);
        assertThat(store.read(first.getJobId(), owner).getMessageKey()).isEqualTo("interrupted");
        assertThat(store.claim(first.getJobId(), 1)).isFalse();
        inTransaction(() -> assertThat(second.claim(next.getJobId(), 1)).isTrue());
        inTransaction(() -> { second.cancel(next.getJobId(), owner); second.release(next.getJobId()); });
    }

    @Test void fencedIngestCommitsItsScanIdOnceAndActiveAgeDoesNotExpireJob() {
        var job = status(); long owner = owner();
        store.reserve(job, owner, job.getJobId(), 2, false);
        jdbc.update("UPDATE import_jobs SET created_at = DATEADD('HOUR', -2, CURRENT_TIMESTAMP) WHERE job_id = ?", job.getJobId());
        store.maintain();
        assertThat(store.read(job.getJobId(), owner)).isNotNull();
        AtomicInteger ingests = new AtomicInteger();
        store.fenced(job.getJobId(), () -> { ingests.incrementAndGet(); return ScanResult.builder().id(987654L).build(); });
        assertThat(store.read(job.getJobId(), owner).getScanResultId()).isEqualTo(987654L);
        assertThatThrownBy(() -> store.fenced(job.getJobId(), () -> { ingests.incrementAndGet(); return null; }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ingests).hasValue(1);
        store.publish(job.toBuilder().scanResultId(987654L).phase(Phase.DONE).build());
        store.publish(job.toBuilder().phase(Phase.CLONING).build());
        assertThat(store.read(job.getJobId(), owner).getPhase()).isEqualTo(Phase.DONE);
    }

    @Test void deferredSlotReleaseIsRecoveredWhileWorkerRemainsAlive() {
        var job = status(); long owner = owner();
        store.reserve(job, owner, job.getJobId(), 2, false);
        assertThat(store.claim(job.getJobId(), 100)).isTrue();
        store.publish(job.toBuilder().phase(Phase.DONE).build());
        store.retryRelease(job.getJobId());
        store.maintain();
        assertThat(jobs.findByJobId(job.getJobId()).orElseThrow().isWorkerActive()).isFalse();
    }
}
