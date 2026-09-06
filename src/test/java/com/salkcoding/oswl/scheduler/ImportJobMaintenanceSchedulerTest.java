package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.service.ingest.ImportJobStore;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImportJobMaintenanceSchedulerTest {
    @Test void slowProgressDoesNotBlockLeaseRenewalAndFailureDoesNotStopTicks() throws Exception {
        var jobs = mock(ImportJobStore.class);
        var imports = mock(QuickImportService.class);
        var heartbeat = new CountDownLatch(2);
        var progressEntered = new CountDownLatch(1);
        var releaseProgress = new CountDownLatch(1);
        doAnswer(call -> {
            heartbeat.countDown();
            if (heartbeat.getCount() == 1) throw new IllegalStateException("temporary database failure");
            return null;
        }).when(jobs).maintain();
        doAnswer(call -> {
            progressEntered.countDown();
            releaseProgress.await();
            return null;
        }).when(imports).maintainDurableJobs();
        var scheduler = new ImportJobMaintenanceScheduler(jobs, imports);
        try {
            scheduler.start();
            assertThat(progressEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(heartbeat.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseProgress.getCount()).isEqualTo(1);
        } finally {
            releaseProgress.countDown();
            scheduler.stop();
        }
    }
}
