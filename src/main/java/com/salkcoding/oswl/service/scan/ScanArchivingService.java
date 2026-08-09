package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.scan.ScanArchiveExportDto;
import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.DependencyPathRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.util.VersionOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Retention policy for old scans — a project's {@code scan_components}/{@code dependency_paths}
 * rows grow without bound over time, so the oldest completed scans beyond a per-project retain
 * count have their component/CVE detail deleted, keeping only the aggregate severity/license
 * counts on {@link ScanResult} itself (see {@link ScanResult#archive}).
 *
 * Manually triggered only (no scheduler) — deleting scan detail is not something to run
 * automatically without an operator deciding the retain count for their own audit/compliance
 * needs first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScanArchivingService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final DependencyPathRepository dependencyPathRepository;
    private final AuditLogService auditLogService;

    @Value("${oswl.archive.retain-scans-per-project:20}")
    private int defaultRetainCount;

    /** Archives {@code projectId}'s completed scans beyond the default retain count. */
    @Transactional
    public ScanArchiveResult archiveProject(Long projectId) {
        return archiveProject(projectId, defaultRetainCount);
    }

    /**
     * Full component/CVE/dependency-path detail for every not-yet-archived scan that {@link
     * #archiveProject(Long, int)} would delete the detail of, at the given retain count.
     * Read-only — call this before archiving to keep a copy, since archiving cannot be undone.
     */
    @Transactional(readOnly = true)
    public List<ScanArchiveExportDto> exportPendingArchive(Long projectId) {
        return exportPendingArchive(projectId, defaultRetainCount);
    }

    @Transactional(readOnly = true)
    public List<ScanArchiveExportDto> exportPendingArchive(Long projectId, int retainCount) {
        if (retainCount < 1) {
            retainCount = defaultRetainCount;
        }
        if (!projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        List<ScanResult> completed = new ArrayList<>(scanResultRepository.findCompletedByProjectId(projectId));
        VersionOrder.sortDesc(completed);

        List<ScanArchiveExportDto> export = new ArrayList<>();
        for (int i = retainCount; i < completed.size(); i++) {
            ScanResult scan = completed.get(i);
            if (scan.isArchived()) continue;
            export.add(exportOneScan(scan));
        }
        return export;
    }

    @Transactional
    public ScanArchiveResult archiveProject(Long projectId, int retainCount) {
        if (retainCount < 1) {
            retainCount = defaultRetainCount;
        }
        if (!projectRepository.existsById(projectId)) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        List<ScanResult> completed = new ArrayList<>(scanResultRepository.findCompletedByProjectId(projectId));
        VersionOrder.sortDesc(completed);

        int archivedNow = 0;
        for (int i = retainCount; i < completed.size(); i++) {
            ScanResult scan = completed.get(i);
            if (scan.isArchived()) continue;
            archiveOneScan(scan);
            archivedNow++;
        }

        if (archivedNow > 0) {
            auditLogService.log("SCAN.ARCHIVE", "PROJECT", projectId.toString(), null,
                    "archivedNow=" + archivedNow + " retainCount=" + retainCount);
            log.info("[ScanArchiving] projectId={} archived {} scan(s), retaining the {} most recent",
                    projectId, archivedNow, retainCount);
        }
        return new ScanArchiveResult(projectId, retainCount, completed.size(), archivedNow);
    }

    private void archiveOneScan(ScanResult scan) {
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());
        int[] security = aggregateSecurity(components);
        int[] license = aggregateLicense(components);
        scan.archive(components.size(), security, license);
        scanResultRepository.save(scan);

        List<Long> componentIds = scanComponentRepository.findIdsByScanResultId(scan.getId());
        if (!componentIds.isEmpty()) {
            dependencyPathRepository.deleteByScanComponentIdIn(componentIds);
        }
        scanComponentRepository.deleteByScanResultId(scan.getId());
    }

    private ScanArchiveExportDto exportOneScan(ScanResult scan) {
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());
        List<ScanArchiveExportDto.ComponentExportDto> componentDtos = components.stream()
                .map(this::toComponentExport)
                .toList();
        return new ScanArchiveExportDto(scan.getId(), scan.getVersion(), scan.getScannedAt(), componentDtos);
    }

    private ScanArchiveExportDto.ComponentExportDto toComponentExport(ScanComponent comp) {
        Library lib = comp.getLibrary();
        List<ScanArchiveExportDto.CveExportDto> cveDtos = lib.getCves().stream()
                .map(cve -> new ScanArchiveExportDto.CveExportDto(
                        cve.getCveId(),
                        cve.getSeverity() != null ? cve.getSeverity().name() : null,
                        cve.getCvssScore(), cve.getEpssScore(), cve.getKevListed(), cve.getFixVersion()))
                .toList();
        List<List<String>> dependencyPaths = dependencyPathRepository
                .findByScanComponentIdOrderByPathIndexAsc(comp.getId()).stream()
                .map(path -> path.getPathNodes().stream()
                        .map(node -> node.getName() + "@" + node.getVersion())
                        .toList())
                .toList();
        return new ScanArchiveExportDto.ComponentExportDto(
                lib.getName(), lib.getVersion(), lib.getEcosystem(),
                lib.getLicenseStatus() != null ? lib.getLicenseStatus().name() : null,
                lib.getLicenseName(), cveDtos, dependencyPaths);
    }

    private static int[] aggregateSecurity(List<ScanComponent> components) {
        int critical = 0, high = 0, medium = 0, low = 0, unscored = 0;
        for (ScanComponent comp : components) {
            for (Cve cve : comp.getLibrary().getCves()) {
                if (cve.getSeverity() == null) { unscored++; continue; }
                switch (cve.getSeverity()) {
                    case CRITICAL -> critical++;
                    case HIGH -> high++;
                    case MEDIUM -> medium++;
                    case LOW -> low++;
                    default -> unscored++;
                }
            }
        }
        return new int[]{critical, high, medium, low, unscored};
    }

    private static int[] aggregateLicense(List<ScanComponent> components) {
        int critical = 0, high = 0, medium = 0, low = 0;
        for (ScanComponent comp : components) {
            Library lib = comp.getLibrary();
            LicenseStatus status = lib.getLicenseStatus();
            if (status == null) { medium++; continue; }
            switch (status) {
                case RESTRICTED -> critical++;
                case CAUTION -> high++;
                case UNKNOWN -> medium++;
                default -> low++;
            }
        }
        return new int[]{critical, high, medium, low};
    }
}
