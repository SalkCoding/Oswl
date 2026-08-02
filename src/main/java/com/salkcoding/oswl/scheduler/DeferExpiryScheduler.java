package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.service.notification.WebhookNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Nightly scheduler that automatically clears expired deferrals.
 * A deferral is considered expired when its deferralExpiresAt timestamp is in the past.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeferExpiryScheduler {

    private final ScanComponentRepository scanComponentRepository;
    private final AuditLogService auditLogService;
    private final WebhookNotificationService webhookNotificationService;

    /**
     * Runs every day at midnight to clear deferrals whose expiry date has passed.
     * {@code @SchedulerLock} is a no-op unless {@code oswl.scheduler-lock.enabled=true}
     * (see {@link SchedulerLockConfig}) — a single instance is unaffected.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "DeferExpiryScheduler_expireOverdueDeferrals",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional
    public void expireOverdueDeferrals() {
        List<ScanComponent> expired = scanComponentRepository.findExpiredDeferrals(LocalDateTime.now());
        if (expired.isEmpty()) {
            log.debug("[DeferExpiry] No expired deferrals found.");
            return;
        }
        log.info("[DeferExpiry] Clearing {} expired deferral(s)", expired.size());
        for (ScanComponent sc : expired) {
            String scanId = sc.getScanResult() != null ? sc.getScanResult().getId().toString() : "?";
            String libName = sc.getLibrary() != null ? sc.getLibrary().getName() : "?";
            auditLogService.logAnonymous("[system]", "COMPONENT.DEFER_EXPIRE",
                    "SCAN_COMPONENT",
                    sc.getId().toString(),
                    libName,
                    "scanResultId=" + scanId);
            sc.expireDeferral();
        }
    }

    /**
     * Runs every day at 09:00 to warn about deferrals expiring within the next 7 days.
     * One aggregated webhook is sent per affected project.
     */
    @Scheduled(cron = "0 0 9 * * *")
    @SchedulerLock(name = "DeferExpiryScheduler_notifyImminentExpirations",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional
    public void notifyImminentExpirations() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusDays(7);
        List<ScanComponent> imminent = scanComponentRepository.findDeferralsExpiringWithin(now, windowEnd);
        if (imminent.isEmpty()) {
            log.debug("[DeferExpiry] No imminent deferral expirations found.");
            return;
        }
        Map<Project, List<ScanComponent>> byProject = imminent.stream()
                .filter(sc -> sc.getScanResult() != null && sc.getScanResult().getProject() != null)
                .collect(Collectors.groupingBy(sc -> sc.getScanResult().getProject()));
        for (Map.Entry<Project, List<ScanComponent>> entry : byProject.entrySet()) {
            Project project = entry.getKey();
            List<String> summaries = entry.getValue().stream()
                    .map(sc -> {
                        String lib = sc.getLibrary() != null ? sc.getLibrary().getName() : "?";
                        String ver = sc.getLibrary() != null ? sc.getLibrary().getVersion() : null;
                        return lib + (ver != null ? " " + ver : "")
                                + " — expires " + sc.getDeferralExpiresAt().toLocalDate();
                    })
                    .toList();
            try {
                webhookNotificationService.sendWaiverExpiryImminent(project, summaries);
            } catch (Exception e) {
                log.error("[DeferExpiry] Webhook notification failed for projectId={}: {}",
                        project.getId(), e.getMessage());
            }
        }
    }
}
