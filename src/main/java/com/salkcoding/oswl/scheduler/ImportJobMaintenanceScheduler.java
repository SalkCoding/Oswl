package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.service.ingest.ImportJobStore;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Keeps leases independent of long nightly jobs and slow progress/SSE consumers. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobMaintenanceScheduler {
    private final ImportJobStore jobs;
    private final QuickImportService imports;
    // Two recurring tasks cannot overlap themselves. A blocked progress task leaves
    // the other thread available for lease renewal and expired-worker recovery.
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().name("import-maintenance-", 0).daemon(true).factory());

    @PostConstruct
    void start() {
        executor.scheduleWithFixedDelay(() -> safely("lease", jobs::maintain), 0, 10, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> safely("progress", imports::maintainDurableJobs), 0, 10, TimeUnit.SECONDS);
    }

    private void safely(String task, Runnable action) {
        try { action.run(); }
        catch (Exception e) { log.warn("[QuickImport] {} maintenance failed; will retry", task, e); }
    }

    @PreDestroy
    void stop() { executor.shutdownNow(); }
}
