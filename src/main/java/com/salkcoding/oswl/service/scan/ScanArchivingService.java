package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.dto.scan.ScanArchiveExportDto;
import com.salkcoding.oswl.dto.scan.ScanArchiveResult;
import com.salkcoding.oswl.dto.scan.ScanAssessment;
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
import java.util.Map;
import java.util.stream.Collectors;
import com.salkcoding.oswl.domain.entity.scan.DependencyPath;

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
    private final ScanSummaryReader summaryReader;

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
        long componentCount = scanComponentRepository.countByScanResultId(scan.getId());
        var summary = summaryReader.read(List.of(scan)).get(scan.getId());
        int[] security = summary.security();
        int[] license = summary.licenses();
        scan.archive(Math.toIntExact(componentCount), security, license, summary.matchReviewCount());
        scanResultRepository.save(scan);

        if (componentCount > 0) {
            dependencyPathRepository.deleteByScanResultId(scan.getId());
        }
        scanComponentRepository.deleteByScanResultId(scan.getId());
    }

    private ScanArchiveExportDto exportOneScan(ScanResult scan) {
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());
        Map<Long, List<DependencyPath>> paths = dependencyPathRepository.findByScanResultId(scan.getId()).stream()
                .collect(Collectors.groupingBy(path -> path.getScanComponent().getId()));
        Map<Long, ScanAssessment.LibraryAssessment> preserved = scan.getAssessmentJson() == null
                ? null : ScanAssessmentService.read(scan.getAssessmentJson()).libraries().stream()
                .collect(Collectors.toMap(ScanAssessment.LibraryAssessment::libraryId,
                        java.util.function.Function.identity()));
        if (preserved != null && !preserved.keySet().equals(components.stream()
                .map(component -> component.getLibrary().getId()).collect(Collectors.toSet()))) {
            throw new IllegalStateException("Preserved scan assessment and component inventory do not match");
        }
        List<ScanArchiveExportDto.ComponentExportDto> componentDtos = components.stream()
                .map(component -> toComponentExport(component, paths.getOrDefault(component.getId(), List.of()), preserved))
                .toList();
        return new ScanArchiveExportDto(scan.getId(), scan.getVersion(), scan.getScannedAt(), componentDtos, scan.getAssessmentJson());
    }

    private ScanArchiveExportDto.ComponentExportDto toComponentExport(ScanComponent comp, List<DependencyPath> paths,
            Map<Long, ScanAssessment.LibraryAssessment> preserved) {
        Library lib = comp.getLibrary();
        var evidence = preserved == null ? ScanAssessmentService.fromLibrary(lib) : preserved.get(lib.getId());
        if (evidence == null) {
            throw new IllegalStateException("Preserved scan assessment does not cover component library " + lib.getId());
        }
        List<ScanArchiveExportDto.CveExportDto> cveDtos = preserved == null ? lib.getCves().stream()
                .map(cve -> new ScanArchiveExportDto.CveExportDto(cve.getCveId(),
                        cve.getSeverity() != null ? cve.getSeverity().name() : null,
                        cve.getCvssScore(), cve.getEpssScore(), cve.getKevListed(), cve.getFixVersion())).toList()
                : evidence.findings().stream()
                .map(cve -> new ScanArchiveExportDto.CveExportDto(
                        cve.cveId(), cve.severity() != null ? cve.severity().name() : null,
                        cve.cvssScore(), cve.epssScore(), cve.kevListed(), cve.fixVersion()))
                .toList();
        List<List<String>> dependencyPaths = paths.stream()
                .map(path -> path.getPathNodes().stream()
                        .map(node -> node.getName() + "@" + node.getVersion())
                        .toList())
                .toList();
        return new ScanArchiveExportDto.ComponentExportDto(
                evidence.name(), evidence.version(), evidence.ecosystem(),
                (preserved == null && lib.getLicenseStatus() == null) ? null : evidence.licenseStatus().name(), evidence.licenseName(), cveDtos, dependencyPaths);
    }

}
