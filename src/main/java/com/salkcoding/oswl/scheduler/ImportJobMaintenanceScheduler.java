package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.service.ingest.ImportJobStore;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keeps leases independent of long nightly jobs and slow progress/SSE consumers. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobMaintenanceScheduler implements SmartLifecycle {
    private final ImportJobStore jobs;
    private final QuickImportService imports;
    // Two recurring tasks cannot overlap themselves. A blocked progress task leaves
    // the other thread available for lease renewal and expired-worker recovery.
    private ScheduledExecutorService executor;
    private volatile boolean running;

    @Override
    public synchronized void start() {
        if (running) return;
        executor = Executors.newScheduledThreadPool(2,
                Thread.ofPlatform().name("import-maintenance-", 0).daemon(true).factory());
        running = true;
        executor.scheduleWithFixedDelay(() -> safely("lease", jobs::maintain), 0, 10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> safely("progress", imports::maintainDurableJobs), 0, 10, TimeUnit.SECONDS);
    }

    private void safely(String task, Runnable action) {
        if (!running) return;
        try { action.run(); }
        catch (Exception e) {
            if (running) log.warn("[QuickImport] {} maintenance failed; will retry", task, e);
            else log.debug("[QuickImport] {} maintenance stopped during shutdown", task, e);
        }
    }

    /** Stop before Spring destroys database resources, and wait for interrupted work to unwind. */
    @Override
    public synchronized void stop() {
        running = false;
        if (executor == null) return;
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[QuickImport] Maintenance tasks did not stop within the shutdown budget");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() { return running; }
}
