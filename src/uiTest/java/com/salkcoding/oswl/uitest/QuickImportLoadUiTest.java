package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real concurrency measurement. {@code QuickImportService.startBatchImport}
 * already exists for exactly this (bypasses the per-user queue cap; concurrency is still gated
 * by {@code max-concurrent}), so no new load-test entry point was needed. The actual git clone
 * is stubbed out as a deliberate, disclosed simplification: submitted URLs use a host
 * {@code parseRepoUrl} won't recognize, so each job fails fast via the existing
 * {@code INVALID_REPO_URL} path with zero network I/O — the concern here is the backpressure
 * machinery (queue admission, the running-count semaphore, DB writes), not whether a real clone
 * succeeds.
 *
 * <p><b>Disclosed limitation:</b> that's exactly what makes "general web responsiveness during
 * the burst" and a real HikariCP stress peak unmeasurable here — 100 stubbed jobs complete in
 * under 250ms (confirmed empirically), which is too short a window for concurrent HTTP probes or
 * sustained connection pressure to show anything meaningful (an earlier version of this test
 * tried exactly that and only ever captured n=0 samples). Reproducing DoD points 3–4 for real
 * would need either a genuine multi-second clone+scan pipeline or an artificial delay hook in
 * production code, neither of which this change adds. What's verified here is completion rate
 * and the concurrency cap never being exceeded (DoD points 1 and, structurally, the basis for
 * point 2 — see {@link QuickImportQueueCapUiTest} for the exact-boundary 429 check).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("uitest")
class QuickImportLoadUiTest {

    @Autowired
    private QuickImportService quickImportService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @Value("${oswl.quick-import.max-concurrent:3}")
    private int maxConcurrentImports;

    @Value("${oswl.quick-import.max-queued-per-user:3}")
    private int maxQueuedPerUser;

    @Test
    @DisplayName("Concurrent import bursts (20/50/100) — completion rate and concurrency-cap adherence")
    void concurrentBurstsCompleteWithoutExceedingTheConcurrencyCap() throws IOException, InterruptedException {
        StringBuilder report = new StringBuilder();
        report.append("configured max-concurrent: ").append(maxConcurrentImports).append('\n');
        report.append("configured max-queued-per-user: ").append(maxQueuedPerUser).append('\n');
        report.append("NOTE: clone is stubbed (unrecognized host -> INVALID_REPO_URL, no network I/O) — see class javadoc.\n");
        report.append("NOTE: web-responsiveness and HikariCP-under-sustained-load were NOT measurable at this speed — see class javadoc.\n\n");

        for (int scale : List.of(20, 50, 100)) {
            report.append(measureBurst(scale));
        }

        Path dir = Path.of("build", "reports", "load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("quick-import-load.txt"), report.toString());
    }

    private String measureBurst(int scale) throws InterruptedException {
        long userId = uniqueUserId();
        AtomicInteger peakRunning = new AtomicInteger(0);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread sampler = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                Number running = meterRegistry.get("oswl.quickimport.running").gauge().value();
                peakRunning.updateAndGet(prev -> Math.max(prev, running.intValue()));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        List<String> urls = new ArrayList<>(scale);
        for (int i = 0; i < scale; i++) {
            urls.add(fakeRepoUrl(i));
        }

        HikariPoolMXBean pool = dataSource instanceof HikariDataSource h ? h.getHikariPoolMXBean() : null;
        AtomicInteger peakActiveConnections = new AtomicInteger(0);
        Thread poolSampler = pool == null ? null : Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                peakActiveConnections.updateAndGet(prev -> Math.max(prev, pool.getActiveConnections()));
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        Instant start = Instant.now();
        List<String> jobIds = quickImportService.startBatchImport(urls, userId);
        assertThat(jobIds).hasSize(scale);

        // Poll until every job reaches a terminal state (DONE/FAILED) or the timeout elapses.
        Duration timeout = Duration.ofSeconds(30);
        Instant deadline = start.plus(timeout);
        int terminalCount = 0;
        while (Instant.now().isBefore(deadline)) {
            terminalCount = (int) jobIds.stream()
                    .map(id -> quickImportService.getJobStatus(id, userId))
                    .filter(s -> s != null && (s.getPhase() == QuickImportJobStatus.Phase.DONE
                            || s.getPhase() == QuickImportJobStatus.Phase.FAILED))
                    .count();
            if (terminalCount == scale) break;
            Thread.sleep(20);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        sampling.set(false);
        sampler.join(1000);
        if (poolSampler != null) poolSampler.join(1000);

        long completionCount = terminalCount;

        assertThat(completionCount)
                .withFailMessage("%d of %d jobs never reached a terminal state within %s", scale - completionCount, scale, timeout)
                .isEqualTo(scale);
        assertThat(peakRunning.get())
                .withFailMessage("observed %d concurrently-running imports, exceeding the configured cap of %d",
                        peakRunning.get(), maxConcurrentImports)
                .isLessThanOrEqualTo(maxConcurrentImports);

        return "scale=%d: completed %d/%d in %dms, peak running=%d (cap=%d), peak HikariCP active=%s\n".formatted(
                        scale, completionCount, scale, elapsed.toMillis(), peakRunning.get(), maxConcurrentImports,
                        pool == null ? "n/a" : String.valueOf(peakActiveConnections.get()));
    }

    private static final AtomicLong USER_ID_SEQ = new AtomicLong(900_000_000L);

    /** A fresh fake userId per test/burst so QuickImportService's in-memory per-user queue state never leaks across cases. */
    private static long uniqueUserId() {
        return USER_ID_SEQ.incrementAndGet();
    }

    /**
     * A host parseRepoUrl won't recognize as GitHub/GitLab/Bitbucket and won't match any stored
     * VCS connection — runImport() fails it via INVALID_REPO_URL before any network I/O, which is
     * a legitimate way to stub the clone step for a concurrency measurement like this one.
     */
    private static String fakeRepoUrl(int i) {
        return "https://load-test.invalid/oswl-loadtest/repo-" + i;
    }
}
