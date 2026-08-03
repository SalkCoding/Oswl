package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.project.ProjectVersion;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.dto.ScanHistoryRowDto;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.project.ProjectVersionRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScanHistoryService {

    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ProjectRepository       projectRepository;
    private final ScanResultRepository    scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final ProjectVersionRepository projectVersionRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId",   projectId);
        model.addAttribute("projectName", project.getName());
        model.addAttribute("scanVersions", java.util.List.of()); // topbar needs this

        List<ScanResult> scans =
                scanResultRepository.findAllByProjectIdOrderByScannedAtDesc(projectId);

        // Batch-load per-scan component counts and per-version import sources (avoids per-row N+1).
        // The count query LEFT JOINs from ScanResult so every scan id is present (0 when no
        // components); any id still missing falls back to the single-scan count as a safety net.
        java.util.Map<Long, Long> componentCounts = new java.util.HashMap<>();
        if (!scans.isEmpty()) {
            List<Long> scanIds = scans.stream().map(ScanResult::getId).toList();
            for (Object[] row : scanComponentRepository.countComponentsByScanResultIds(scanIds)) {
                componentCounts.put((Long) row[0], (Long) row[1]);
            }
        }
        java.util.Map<String, String> importSources = new java.util.HashMap<>();
        List<String> versions = scans.stream()
                .map(ScanResult::getVersion)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!versions.isEmpty()) {
            for (ProjectVersion pv : projectVersionRepository.findByProjectAndBranchIn(project, versions)) {
                importSources.put(pv.getBranch(), pv.getImportSource().name());
            }
        }

        List<ScanHistoryRowDto> rows = scans.stream()
                .map(s -> {
                    Long batchedCount = componentCounts.get(s.getId());
                    long componentCount = batchedCount != null
                            ? batchedCount
                            : scanComponentRepository.countByScanResultId(s.getId());
                    String importSource = null;
                    if (s.getVersion() != null) {
                        if (importSources.containsKey(s.getVersion())) {
                            importSource = importSources.get(s.getVersion());
                        } else {
                            importSource = projectVersionRepository
                                    .findByProjectAndBranch(project, s.getVersion())
                                    .map(pv -> pv.getImportSource().name())
                                    .orElse(null);
                            // Memoize (null = no ProjectVersion row) so repeated versions don't re-query
                            importSources.put(s.getVersion(), importSource);
                        }
                    }
                    return ScanHistoryRowDto.builder()
                            .scanId(s.getId())
                            .version(s.getVersion() != null ? s.getVersion() : "-")
                            .status(s.getStatus().name())
                            .scannedAt(s.getScannedAt() != null
                                    ? s.getScannedAt().format(DISPLAY_FMT) : "-")
                            .componentCount(componentCount)
                            .errorMessage(s.getErrorMessage())
                            .importSource(importSource)
                            .build();
                })
                .toList();

        model.addAttribute("scanRows",   rows);
        model.addAttribute("totalScans", rows.size());
        log.debug("[ScanHistory] projectId={} loaded {} scan records", projectId, rows.size());
    }
}
