package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.service.ContinuousMonitoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly scheduler that re-checks all monitored components against OSV and raises
 * alerts for vulnerabilities published after the last scan.
 *
 * Gated by {@code oswl.monitoring.enabled}; cron overridable via {@code oswl.monitoring.cron}.
 * One retry on cycle failure — the OSV client already degrades gracefully (empty results)
 * on network errors, so a hard failure here means something unexpected worth retrying once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContinuousMonitoringScheduler {

    private final ContinuousMonitoringService continuousMonitoringService;

    @Value("${oswl.monitoring.enabled:true}")
    private boolean enabled;

    /**
     * Runs every night (default 03:00) after the deferral-expiry scheduler.
     * {@code @SchedulerLock} is a no-op unless {@code oswl.scheduler-lock.enabled=true}
     * (see {@link SchedulerLockConfig}) — a single instance is unaffected.
     */
    @Scheduled(cron = "${oswl.monitoring.cron:0 0 3 * * *}")
    @SchedulerLock(name = "ContinuousMonitoringScheduler_runNightlyMonitoring",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    public void runNightlyMonitoring() {
        if (!enabled) {
            log.debug("[Monitor] Continuous monitoring disabled (oswl.monitoring.enabled=false) — skipping.");
            return;
        }
        try {
            continuousMonitoringService.runCycle();
        } catch (Exception e) {
            log.error("[Monitor] Monitoring cycle failed: {} — retrying once", e.getMessage(), e);
            try {
                continuousMonitoringService.runCycle();
            } catch (Exception retryEx) {
                log.error("[Monitor] Monitoring retry failed: {}", retryEx.getMessage(), retryEx);
            }
        }
    }
}
