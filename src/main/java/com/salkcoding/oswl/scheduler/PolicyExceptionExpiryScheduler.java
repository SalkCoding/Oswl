package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import com.salkcoding.oswl.repository.policy.PolicyExceptionRepository;
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
 * Nightly scheduler that automatically expires overdue policy exceptions (waivers)
 * so a gate that once passed because of an approved exception starts failing again once that
 * exception's expiry date has passed — the DoD explicitly requires this to happen without
 * manual intervention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyExceptionExpiryScheduler {

    private final PolicyExceptionRepository policyExceptionRepository;
    private final AuditLogService auditLogService;
    private final WebhookNotificationService webhookNotificationService;

    /**
     * Runs every day at midnight to transition approved exceptions whose expiry has passed to
     * {@code EXPIRED}. {@code @SchedulerLock} is a no-op unless
     * {@code oswl.scheduler-lock.enabled=true} (see {@link SchedulerLockConfig}) — a single
     * instance is unaffected.
     */
    @Scheduled(cron = "0 0 0 * * *")
    @SchedulerLock(name = "PolicyExceptionExpiryScheduler_expireOverdueExceptions",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional
    public void expireOverdueExceptions() {
        List<PolicyException> expired = policyExceptionRepository.findByStatusAndExpiryLessThanEqual(
                PolicyExceptionStatus.APPROVED, LocalDateTime.now());
        if (expired.isEmpty()) {
            log.debug("[PolicyExceptionExpiry] No expired policy exceptions found.");
            return;
        }
        log.info("[PolicyExceptionExpiry] Expiring {} approved policy exception(s)", expired.size());
        for (PolicyException ex : expired) {
            auditLogService.logAnonymous("[system]", "POLICY_EXCEPTION.EXPIRE", "PROJECT",
                    ex.getProject().getId().toString(), ex.getProject().getName(),
                    "policyExceptionId=" + ex.getId() + " reason=" + ex.getReason());
            ex.expire();
        }
    }

    /**
     * Runs every day at 09:00 to warn about approved exceptions expiring within the next 7 days.
     * One aggregated webhook is sent per affected project.
     */
    @Scheduled(cron = "0 0 9 * * *")
    @SchedulerLock(name = "PolicyExceptionExpiryScheduler_notifyImminentExpirations",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    @Transactional(readOnly = true)
    public void notifyImminentExpirations() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime windowEnd = now.plusDays(7);
        List<PolicyException> imminent = policyExceptionRepository.findByStatusAndExpiryBetween(
                PolicyExceptionStatus.APPROVED, now, windowEnd);
        if (imminent.isEmpty()) {
            log.debug("[PolicyExceptionExpiry] No imminent policy exception expirations found.");
            return;
        }
        Map<Project, List<PolicyException>> byProject = imminent.stream()
                .collect(Collectors.groupingBy(PolicyException::getProject));
        for (Map.Entry<Project, List<PolicyException>> entry : byProject.entrySet()) {
            Project project = entry.getKey();
            List<String> summaries = entry.getValue().stream()
                    .map(ex -> ex.getReason() + " — expires " + ex.getExpiry().toLocalDate())
                    .toList();
            try {
                webhookNotificationService.sendWaiverExpiryImminent(project, summaries);
            } catch (Exception e) {
                log.error("[PolicyExceptionExpiry] Webhook notification failed for projectId={}: {}",
                        project.getId(), e.getMessage());
            }
        }
    }
}
