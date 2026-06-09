package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    /** Runs every day at midnight to clear deferrals whose expiry date has passed. */
    @Scheduled(cron = "0 0 0 * * *")
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
            sc.clearDeferral();
        }
    }
}
