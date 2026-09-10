package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.enums.Reachability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.nio.file.Path;

/** Runs file analysis without holding a database transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceReachabilityService {
    private final SourceReferenceAnalyzer sourceReferenceAnalyzer;
    private final SourceReachabilityStore store;

    public void analyzeAndPersist(Path cloneDir, Long scanResultId) {
        try {
            long loadStarted = System.nanoTime();
            var candidates = store.candidates(scanResultId);
            long loadMillis = (System.nanoTime() - loadStarted) / 1_000_000;
            if (candidates.isEmpty()) return;
            boolean transactionDuringFiles = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            var index = sourceReferenceAnalyzer.index(cloneDir);
            var updates = candidates.stream().map(candidate -> {
                var result = index.match(candidate.language(), candidate.names());
                return new SourceReachabilityStore.Update(candidate.id(), result,
                        index.description(candidate.language(), result.reachability() == Reachability.REACHABLE, candidate.names().isEmpty()));
            }).toList();
            long saveStarted = System.nanoTime();
            for (int offset = 0; offset < updates.size(); offset += 200)
                store.save(updates.subList(offset, Math.min(offset + 200, updates.size())));
            long saveMillis = (System.nanoTime() - saveStarted) / 1_000_000;
            log.info("[Reachability][Source] scanId={} components={} files={} bytes={} durationMs={} limitations={} loadMs={} saveMs={} transactionDuringFiles={}",
                    scanResultId, updates.size(), index.files(), index.bytesRead(), index.elapsedMillis(), index.limitations(),
                    loadMillis, saveMillis, transactionDuringFiles);
        } catch (Exception e) {
            log.warn("[Reachability][Source] scanId={} analysis failed: {}", scanResultId, e.getMessage());
        }
    }
}
