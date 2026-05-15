package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.DependencyPath;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.DependencyPathRepository;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Receives a CLI scan payload, saves ScanComponents (linked to shared Library rows),
 * and immediately returns so the HTTP response is fast.
 * The full vulnerability analysis is then performed asynchronously by
 * VulnerabilityEnrichmentService.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanIngestService {

    private final ScanResultRepository            scanResultRepository;
    private final ScanComponentRepository         scanComponentRepository;
    private final DependencyPathRepository        dependencyPathRepository;
    private final LibraryRepository               libraryRepository;
    private final ProjectRepository               projectRepository;
    private final VulnerabilityEnrichmentService  enrichmentService;

    /**
     * Persists the scan payload and kicks off async enrichment.
     *
     * @param projectId project ID from the authenticated API key
     * @param payload   CLI payload
     * @return persisted ScanResult (status=SCANNING before async enrichment starts)
     */
    @Transactional
    public ScanResult ingest(Long projectId, ScanPayload payload) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Same project + same version → upsert (clear old components, re-run analysis).
        // Same project + different version → always create a new ScanResult row.
        String incomingVersion = payload.getVersion();
        ScanResult scanResult;
        if (incomingVersion != null) {
            scanResult = scanResultRepository.findByProjectIdAndVersion(projectId, incomingVersion)
                    .map(existing -> {
                        existing.getComponents().clear();
                        existing.resetForRescan(payload.getRawJson());
                        return existing;
                    })
                    .orElseGet(() -> scanResultRepository.save(ScanResult.builder()
                            .project(project)
                            .version(incomingVersion)
                            .rawPayload(payload.getRawJson())
                            .build()));
        } else {
            scanResult = scanResultRepository.save(ScanResult.builder()
                    .project(project)
                    .version(null)
                    .rawPayload(payload.getRawJson())
                    .build());
        }

        scanResult.startScanning();
        scanResultRepository.save(scanResult);

        // Save ScanComponents — upsert Library rows as needed
        if (payload.getComponents() != null) {
            for (ScanPayload.ComponentPayload cp : payload.getComponents()) {
                Library library = findOrCreateLibrary(cp);
                ScanComponent sc = ScanComponent.builder()
                        .scanResult(scanResult)
                        .library(library)
                        .dependencyInfo(cp.getDependencyInfo())
                        .reviewed(false)
                        .ignored(false)
                        .build();
                scanComponentRepository.save(sc);

                // Persist full dependency path trees if the CLI sent them
                if (cp.getDependencyPaths() != null && !cp.getDependencyPaths().isEmpty()) {
                    int pathIdx = 0;
                    for (List<ScanPayload.DependencyNodeRef> rawPath : cp.getDependencyPaths()) {
                        if (rawPath == null || rawPath.isEmpty()) continue;
                        List<DependencyPath.PathNode> nodes = rawPath.stream()
                                .map(n -> new DependencyPath.PathNode(
                                        n.getName(), n.getVersion()))
                                .toList();
                        dependencyPathRepository.save(DependencyPath.builder()
                                .scanComponent(sc)
                                .pathIndex(pathIdx++)
                                .pathNodes(nodes)
                                .depth(nodes.size())
                                .build());
                    }
                }
            }
        }

        project.updateLastScanned(payload.getVersion(), LocalDateTime.now());

        log.info("[ScanIngest] projectId={} version={} components={} status=SCANNING — enrichment pending",
                projectId, payload.getVersion(),
                payload.getComponents() != null ? payload.getComponents().size() : 0);

        // Fire async enrichment AFTER the current transaction commits
        // (otherwise the async thread can't find the ScanResult in DB)
        final Long scanResultId = scanResult.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    enrichmentService.enrich(scanResultId);
                } catch (Exception ex) {
                    // This catches task-submission failures (e.g. executor rejected).
                    // Mark the scan as FAILED asynchronously so the UI does not hang.
                    log.error("[ScanIngest] Failed to submit enrichment task for scanId={}: {}",
                            scanResultId, ex.getMessage(), ex);
                    enrichmentService.failScan(scanResultId,
                            "Enrichment task submission failed: " + ex.getMessage());
                }
            }
        });

        return scanResult;
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Library findOrCreateLibrary(ScanPayload.ComponentPayload cp) {
        String eco = cp.getEcosystem().toUpperCase();
        return libraryRepository
                .findByNameAndVersionAndEcosystem(cp.getName(), cp.getVersion(), eco)
                .orElseGet(() -> libraryRepository.save(Library.builder()
                        .name(cp.getName())
                        .version(cp.getVersion())
                        .ecosystem(eco)
                        .licenseStatus(LicenseStatus.UNKNOWN)
                        .build()));
    }
}

