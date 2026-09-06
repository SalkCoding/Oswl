package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.domain.entity.scan.ScanFinding;
import com.salkcoding.oswl.dto.scan.ScanFindingCandidate;
import com.salkcoding.oswl.repository.scan.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SourceFindingStore {
    private final ScanResultRepository scans;
    private final ScanFindingRepository findings;

    @Transactional
    public boolean persist(Long scanId, Long pendingId, List<ScanFindingCandidate> candidates) {
        var scan = scans.lockForSourceWrite(scanId).orElseThrow();
        if (pendingId != null && findings.findById(pendingId)
                .filter(f -> f.getScanResult().getId().equals(scanId) && "source-scan-pending".equals(f.getRuleId())).isEmpty()) return false;
        findings.saveAll(candidates.stream().map(c -> ScanFinding.builder().scanResult(scan).type(c.type())
                .ruleId(c.ruleId()).severity(c.severity()).filePath(c.filePath()).lineNumber(c.lineNumber())
                .description(c.description()).fingerprint(c.fingerprint()).build()).toList());
        return true;
    }
}
