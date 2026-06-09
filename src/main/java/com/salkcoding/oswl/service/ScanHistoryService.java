package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectVersion;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.dto.ScanHistoryRowDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ProjectVersionRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
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

        List<ScanHistoryRowDto> rows = scans.stream()
                .map(s -> {
                    String importSource = null;
                    if (s.getVersion() != null) {
                        importSource = projectVersionRepository
                                .findByProjectAndBranch(project, s.getVersion())
                                .map(pv -> pv.getImportSource().name())
                                .orElse(null);
                    }
                    return ScanHistoryRowDto.builder()
                            .scanId(s.getId())
                            .version(s.getVersion() != null ? s.getVersion() : "-")
                            .status(s.getStatus().name())
                            .scannedAt(s.getScannedAt() != null
                                    ? s.getScannedAt().format(DISPLAY_FMT) : "-")
                            .componentCount(scanComponentRepository.countByScanResultId(s.getId()))
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
