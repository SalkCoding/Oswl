package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.service.ingest.ImportJobStore;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.support.GenericApplicationContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ImportJobMaintenanceSchedulerTest {
    @Test void contextCloseWaitsForMaintenanceBeforeDestroyingDatabaseResources() throws Exception {
        var jobs = mock(ImportJobStore.class);
        var imports = mock(QuickImportService.class);
        var entered = new CountDownLatch(1);
        var cleanupStarted = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var databaseClosed = new AtomicBoolean();
        doAnswer(call -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                cleanupStarted.countDown();
                releaseCleanup.await();
            }
            return null;
        }).when(jobs).maintain();
        var scheduler = new ImportJobMaintenanceScheduler(jobs, imports);
        var context = new GenericApplicationContext();
        context.registerBean(ImportJobMaintenanceScheduler.class, () -> scheduler);
        context.registerBean("databaseResource", DisposableBean.class, () -> () -> databaseClosed.set(true));
        Thread closer = new Thread(context::close);
        try {
            context.refresh();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            closer.start();
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(databaseClosed).isFalse();
            releaseCleanup.countDown();
            closer.join(5000);
            assertThat(closer.isAlive()).isFalse();
            assertThat(databaseClosed).isTrue();
            assertThat(scheduler.isRunning()).isFalse();
        } finally {
            releaseCleanup.countDown();
            closer.join(5000);
            context.close();
        }
    }

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
