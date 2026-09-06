package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.domain.entity.scan.ScanFinding;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import com.salkcoding.oswl.dto.scan.ScanFindingCandidate;
import com.salkcoding.oswl.repository.scan.ScanFindingRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.service.iacscan.IacScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the secret + IaC misconfiguration scan against a Quick Import clone and
 * persists the results. Runs synchronously right after the scan is ingested, while the clone
 * directory still exists — before {@code CloneCleanupService} deletes it. Never re-clones or
 * keeps its own copy of the working tree.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecretIacScanService {

    private final SecretScanner secretScanner;
    private final IacScanner iacScanner;
    private final CustomRuleScanner customRuleScanner;
    private final ScanFindingRepository scanFindingRepository;
    private final ScanResultRepository scanResultRepository;
    private final SourceFindingStore findingStore;

    @Value("${oswl.secretscan.enabled:true}")
    private boolean enabled;

    /**
     * Scans {@code cloneDir} and persists findings against {@code scanResultId}. Never throws —
     * a scanner failure is logged and swallowed so it cannot fail the Quick Import job itself.
     */
    public boolean scanAndPersist(Path cloneDir, Long scanResultId) { return scanAndPersist(cloneDir, scanResultId, null); }

    public boolean scanAndPersist(Path cloneDir, Long scanResultId, Long pendingId) {
        if (!enabled) return true;
        try {
            List<ScanFindingCandidate> candidates = new ArrayList<>();
            collect(candidates, () -> secretScanner.scan(cloneDir), ScanFindingType.SECRET, "secret");
            collect(candidates, () -> iacScanner.scan(cloneDir), ScanFindingType.IAC, "iac");
            collect(candidates, () -> customRuleScanner.scan(cloneDir), ScanFindingType.IAC, "custom");


            if (!findingStore.persist(scanResultId, pendingId, candidates)) return false;

            long secretCount = candidates.stream().filter(c -> c.type() == ScanFindingType.SECRET).count();
            long iacCount = candidates.size() - secretCount;
            log.info("[SecretIacScan] scanResultId={} persisted {} findings ({} secret, {} iac)",
                    scanResultId, candidates.size(), secretCount, iacCount);
            return true;
        } catch (Exception e) {
            log.warn("[SecretIacScan] scan failed for scanResultId={}: {}", scanResultId, e.getMessage(), e);
            return false;
        }
    }

    /** Called inside fenced ingest, before enrichment can start after commit. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Long markPending(Long scanId) {
        return scanFindingRepository.saveAndFlush(ScanFinding.builder().scanResult(scanResultRepository.getReferenceById(scanId))
                .type(ScanFindingType.IAC).ruleId("source-scan-pending")
                .severity(com.salkcoding.oswl.domain.enums.RiskLevel.HIGH).filePath(".")
                .description("Source inspection has not completed").build()).getId();
    }

    @org.springframework.transaction.annotation.Transactional
    public void completeSourceScan(Long scanId, Long pendingId) { if (pendingId != null) scanFindingRepository.deleteSourceScanPending(scanId, pendingId); }

    private void collect(List<ScanFindingCandidate> results, java.util.function.Supplier<List<ScanFindingCandidate>> scan,
                         ScanFindingType type, String scanner) {
        try { results.addAll(scan.get()); }
        catch (RuntimeException e) {
            log.warn("[SecretIacScan] {} scanner failed; retaining other findings", scanner);
            results.add(new ScanFindingCandidate(type, scanner + "-scan-incomplete",
                    com.salkcoding.oswl.domain.enums.RiskLevel.HIGH, ".", null,
                    "Scan incomplete: the scanner could not inspect all inputs", null));
        }
    }
}
