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
 * The enrichment pipeline runs without an outer transaction (staged commits), so this
 * marker guarantees the FAILED status — and the SSE notification after its commit —
 * is written independently of any partially-applied pipeline state.
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
