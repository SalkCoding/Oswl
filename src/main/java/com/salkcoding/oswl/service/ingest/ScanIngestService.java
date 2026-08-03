package com.salkcoding.oswl.service.ingest;
import com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService;
import com.salkcoding.oswl.service.apikey.ProjectCliKeyPolicyService;

import com.salkcoding.oswl.domain.entity.scan.DependencyPath;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.scan.DependencyPathRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ProjectCliKeyPolicyService      projectCliKeyPolicyService;

    /**
     * Persists the scan payload and kicks off async enrichment.
     *
     * @param projectId project ID from the authenticated API key or Quick Import
     * @param payload   CLI payload
     * @return persisted ScanResult (status=SCANNING before async enrichment starts)
     */
    @Transactional
    public ScanResult ingest(Long projectId, ScanPayload payload) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        projectCliKeyPolicyService.assertScanIngestAllowed(projectId);

        // Same project + same version → upsert (clear old components, re-run analysis).
        // Same project + different version → always create a new ScanResult row.
        String incomingVersion = payload.getVersion();
        ScanResult scanResult;
        boolean rescan = false;
        if (incomingVersion != null) {
            var existingOpt = scanResultRepository.findByProjectIdAndVersion(projectId, incomingVersion);
            if (existingOpt.isPresent()) {
                rescan = true;
                ScanResult existing = existingOpt.get();
                existing.getComponents().clear();
                existing.resetForRescan();
                scanResult = existing;
            } else {
                scanResult = scanResultRepository.save(ScanResult.builder()
                        .project(project)
                        .version(incomingVersion)
                        .build());
            }
        } else {
            scanResult = scanResultRepository.save(ScanResult.builder()
                    .project(project)
                    .version(null)
                    .build());
        }

        // Capture the requester's UI locale so async AI enrichment answers in their language.
        scanResult.recordAiLocale(
                org.springframework.context.i18n.LocaleContextHolder.getLocale().getLanguage());
        scanResult.startScanning();
        scanResultRepository.save(scanResult);

        // Save ScanComponents — resolve all Library rows with one bulk query first
        if (payload.getComponents() != null && !payload.getComponents().isEmpty()) {
            Map<LibraryKey, Library> librariesByKey = resolveLibraries(payload.getComponents());

            List<ScanComponent> scanComponents = new ArrayList<>();
            List<DependencyPath> dependencyPaths = new ArrayList<>();
            for (ScanPayload.ComponentPayload cp : payload.getComponents()) {
                Library library = librariesByKey.get(keyOf(cp));
                ScanComponent sc = ScanComponent.builder()
                        .scanResult(scanResult)
                        .library(library)
                        .dependencyInfo(cp.getDependencyInfo())
                        .scope(cp.getScope())
                        .reviewed(false)
                        .ignored(false)
                        .build();
                scanComponents.add(sc);

                // Persist full dependency path trees if the CLI sent them
                if (cp.getDependencyPaths() != null && !cp.getDependencyPaths().isEmpty()) {
                    int pathIdx = 0;
                    for (List<ScanPayload.DependencyNodeRef> rawPath : cp.getDependencyPaths()) {
                        if (rawPath == null || rawPath.isEmpty()) continue;
                        List<DependencyPath.PathNode> nodes = rawPath.stream()
                                .map(n -> new DependencyPath.PathNode(
                                        n.getName(), n.getVersion()))
                                .toList();
                        dependencyPaths.add(DependencyPath.builder()
                                .scanComponent(sc)
                                .pathIndex(pathIdx++)
                                .pathNodes(nodes)
                                .depth(nodes.size())
                                .build());
                    }
                }
            }
            // Chunked bulk save — ScanComponents first so DependencyPaths can reference their IDs
            saveInChunks(scanComponentRepository, scanComponents);
            saveInChunks(dependencyPathRepository, dependencyPaths);
        }

        log.info("[ScanIngest] projectId={} scanId={} version={} components={} rescan={} status=SCANNING — enrichment pending",
                projectId, scanResult.getId(), payload.getVersion(),
                payload.getComponents() != null ? payload.getComponents().size() : 0, rescan);
        log.debug("[ScanIngest] scanId={} mode={}", scanResult.getId(), rescan ? "rescan" : "new");

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

    private static final int SAVE_CHUNK_SIZE = 500;

    /** Natural key of a shared Library row: (name, version, ecosystem-uppercase). */
    private record LibraryKey(String name, String version, String ecosystem) {}

    private static LibraryKey keyOf(ScanPayload.ComponentPayload cp) {
        return new LibraryKey(cp.getName(), cp.getVersion(), cp.getEcosystem().toUpperCase());
    }

    /**
     * Resolves every distinct (name, version, ecosystem) key in the payload to a Library row:
     * one name-IN bulk query filtered down to exact keys in memory, then a single saveAll()
     * for the rows that do not exist yet.
     */
    private Map<LibraryKey, Library> resolveLibraries(List<ScanPayload.ComponentPayload> components) {
        Map<LibraryKey, ScanPayload.ComponentPayload> distinctByKey = new LinkedHashMap<>();
        for (ScanPayload.ComponentPayload cp : components) {
            distinctByKey.putIfAbsent(keyOf(cp), cp);
        }

        Map<LibraryKey, Library> resolved = new HashMap<>();
        List<String> names = distinctByKey.keySet().stream().map(LibraryKey::name).distinct().toList();
        for (Library library : libraryRepository.findByNameIn(names)) {
            LibraryKey key = new LibraryKey(library.getName(), library.getVersion(), library.getEcosystem());
            if (distinctByKey.containsKey(key)) {
                resolved.put(key, library);
            }
        }

        Map<LibraryKey, ScanPayload.ComponentPayload> missingByKey = new LinkedHashMap<>(distinctByKey);
        missingByKey.keySet().removeAll(resolved.keySet());
        if (!missingByKey.isEmpty()) {
            for (Library library : createLibraries(missingByKey)) {
                resolved.put(new LibraryKey(library.getName(), library.getVersion(), library.getEcosystem()), library);
            }
        }

        // Manifest-declared licenses (composer.lock's license field): deps.dev does not cover
        // these ecosystems, so without this the license column would stay empty forever.
        // Never overwrite a license deps.dev already supplied — only fill blank ones.
        for (Map.Entry<LibraryKey, ScanPayload.ComponentPayload> e : distinctByKey.entrySet()) {
            List<String> manifestLicenses = e.getValue().getLicenses();
            if (manifestLicenses == null || manifestLicenses.isEmpty()) {
                continue;
            }
            Library library = resolved.get(e.getKey());
            if (library == null || (library.getLicenseName() != null && !library.getLicenseName().isBlank())) {
                continue;
            }
            library.updateLicense(String.join(" AND ", manifestLicenses), manifestLicenses, LicenseStatus.UNKNOWN);
            libraryRepository.save(library);
        }
        return resolved;
    }

    /**
     * Inserts the missing Library rows in one saveAll(). When a concurrent ingest commits the same
     * (name, version, ecosystem) first, the unique constraint violation is caught and each row goes
     * through the original per-row save + re-read fallback, so the winner's row is reused (once)
     * instead of failing the scan with a 500.
     */
    private List<Library> createLibraries(Map<LibraryKey, ScanPayload.ComponentPayload> missingByKey) {
        List<Library> missing = missingByKey.entrySet().stream()
                .map(e -> Library.builder()
                        .name(e.getValue().getName())
                        .version(e.getValue().getVersion())
                        .ecosystem(e.getKey().ecosystem())
                        .licenseStatus(LicenseStatus.UNKNOWN)
                        .build())
                .toList();
        try {
            libraryRepository.saveAll(missing);
            return missing;
        } catch (DataIntegrityViolationException duplicate) {
            List<Library> created = new ArrayList<>(missingByKey.size());
            for (Map.Entry<LibraryKey, ScanPayload.ComponentPayload> e : missingByKey.entrySet()) {
                created.add(createLibrary(e.getValue(), e.getKey().ecosystem()));
            }
            return created;
        }
    }

    /**
     * Inserts the Library row. When a concurrent ingest commits the same
     * (name, version, ecosystem) first, the unique constraint violation is caught
     * and the winner's row is re-read (once) instead of failing the scan with a 500.
     * The IDENTITY key makes the INSERT execute (and fail) right at save().
     */
    private Library createLibrary(ScanPayload.ComponentPayload cp, String eco) {
        try {
            return libraryRepository.save(Library.builder()
                    .name(cp.getName())
                    .version(cp.getVersion())
                    .ecosystem(eco)
                    .licenseStatus(LicenseStatus.UNKNOWN)
                    .build());
        } catch (DataIntegrityViolationException duplicate) {
            return libraryRepository
                    .findByNameAndVersionAndEcosystem(cp.getName(), cp.getVersion(), eco)
                    .orElseThrow(() -> duplicate);
        }
    }

    /** Persists in fixed-size chunks so a large payload does not build one giant saveAll() batch. */
    private <T> void saveInChunks(JpaRepository<T, ?> repository, List<T> entities) {
        for (int from = 0; from < entities.size(); from += SAVE_CHUNK_SIZE) {
            repository.saveAll(entities.subList(from, Math.min(from + SAVE_CHUNK_SIZE, entities.size())));
        }
    }
}

