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
import org.springframework.transaction.annotation.Transactional;

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

    @Value("${oswl.secretscan.enabled:true}")
    private boolean enabled;

    /**
     * Scans {@code cloneDir} and persists findings against {@code scanResultId}. Never throws —
     * a scanner failure is logged and swallowed so it cannot fail the Quick Import job itself.
     */
    @Transactional
    public void scanAndPersist(Path cloneDir, Long scanResultId) {
        if (!enabled) return;
        try {
            List<ScanFindingCandidate> candidates = new ArrayList<>();
            candidates.addAll(secretScanner.scan(cloneDir));
            candidates.addAll(iacScanner.scan(cloneDir));
            candidates.addAll(customRuleScanner.scan(cloneDir));
            if (candidates.isEmpty()) return;

            ScanResult ref = scanResultRepository.getReferenceById(scanResultId);
            List<ScanFinding> entities = candidates.stream()
                    .map(c -> ScanFinding.builder()
                            .scanResult(ref)
                            .type(c.type())
                            .ruleId(c.ruleId())
                            .severity(c.severity())
                            .filePath(c.filePath())
                            .lineNumber(c.lineNumber())
                            .description(c.description())
                            .fingerprint(c.fingerprint())
                            .build())
                    .toList();
            scanFindingRepository.saveAll(entities);

            long secretCount = candidates.stream().filter(c -> c.type() == ScanFindingType.SECRET).count();
            long iacCount = candidates.size() - secretCount;
            log.info("[SecretIacScan] scanResultId={} persisted {} findings ({} secret, {} iac)",
                    scanResultId, entities.size(), secretCount, iacCount);
        } catch (Exception e) {
            log.warn("[SecretIacScan] scan failed for scanResultId={}: {}", scanResultId, e.getMessage(), e);
        }
    }
}
