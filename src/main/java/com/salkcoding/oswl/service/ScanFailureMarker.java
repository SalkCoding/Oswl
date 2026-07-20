package com.salkcoding.oswl.service;

import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Marks a scan as FAILED in a fresh (REQUIRES_NEW) transaction.
 *
 * The enrichment pipeline catches catastrophic failures while its own transaction is already
 * doomed (rollback-only, or holding uncommitted state such as the ANALYZING transition).
 * Writing the FAILED status in that transaction would be rolled back with it — or overwritten
 * by the outer commit — leaving the scan stuck in SCANNING/ANALYZING forever. Suspending the
 * doomed transaction and writing in a new one guarantees the status, and the SSE notification
 * after its commit, actually lands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanFailureMarker {

    private final ScanResultRepository    scanResultRepository;
    private final ScanStatusEmitterRegistry scanStatusEmitterRegistry;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long scanResultId, String reason) {
        scanResultRepository.findById(scanResultId).ifPresent(scan -> {
            Long projectId = scan.getProject().getId(); // capture inside tx (LAZY)
            scan.fail(reason);
            scanResultRepository.save(scan);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    scanStatusEmitterRegistry.notifyStatus(projectId, "FAILED");
                }
            });
        });
        log.warn("[Enrich] scanId={} marked FAILED: {}", scanResultId, reason);
    }
}
