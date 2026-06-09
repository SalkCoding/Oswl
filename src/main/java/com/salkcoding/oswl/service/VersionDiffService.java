package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.ai.AiAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionDiffService {

    private final ProjectRepository        projectRepository;
    private final ScanResultRepository     scanResultRepository;
    private final ScanVersionDiffAnalyzer  scanVersionDiffAnalyzer;
    private final AiAnalysisService        aiAnalysisService;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long fromScanId, Long toScanId, Model model) {
        log.debug("[VersionDiff] projectId={} fromScanId={} toScanId={}", projectId, fromScanId, toScanId);
        Project project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId",   projectId);
        model.addAttribute("projectName", project.getName());

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion()
                                : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null
                                ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(false)
                        .build())
                .toList();
        model.addAttribute("scanVersions", scanVersions);

        ScanResult fromScan = null;
        ScanResult toScan   = null;

        if (fromScanId != null) {
            fromScan = allScans.stream().filter(s -> s.getId().equals(fromScanId)).findFirst().orElse(null);
        }
        if (toScanId != null) {
            toScan = allScans.stream().filter(s -> s.getId().equals(toScanId)).findFirst().orElse(null);
        }

        if (toScan == null && !allScans.isEmpty())   toScan   = allScans.get(0);
        if (fromScan == null && allScans.size() > 1) fromScan = allScans.get(1);
        if (fromScan == null)                        fromScan = toScan;

        String fromVersion = fromScan != null ? versionLabel(fromScan) : "-";
        String toVersion   = toScan   != null ? versionLabel(toScan)   : "-";

        model.addAttribute("fromScanId",   fromScan != null ? fromScan.getId() : null);
        model.addAttribute("toScanId",     toScan   != null ? toScan.getId()   : null);
        model.addAttribute("fromVersion",  fromVersion);
        model.addAttribute("toVersion",    toVersion);

        if (fromScan == null || toScan == null || fromScan.getId().equals(toScan.getId())) {
            model.addAttribute("diffRows", List.of());
            model.addAttribute("totalCount",      0);
            model.addAttribute("addedCount",      0);
            model.addAttribute("removedCount",    0);
            model.addAttribute("updatedCount",    0);
            model.addAttribute("newThreatCount",  0);
            model.addAttribute("diffAiInsight",   null);
            return;
        }

        ScanVersionDiffAnalyzer.DiffResult diff =
                scanVersionDiffAnalyzer.compare(fromScan.getId(), toScan.getId());
        List<VersionDiffRowDto> rows = diff.rows();

        log.debug("[VersionDiff] projectId={} from='{}' to='{}': total={} added={} removed={} updated={} newThreat={}",
                projectId, fromVersion, toVersion, rows.size(),
                diff.added(), diff.removed(), diff.updated(), diff.newThreats());
        model.addAttribute("diffRows",       rows);
        model.addAttribute("totalCount",     rows.size());
        model.addAttribute("addedCount",     diff.added());
        model.addAttribute("removedCount",   diff.removed());
        model.addAttribute("updatedCount",   diff.updated());
        model.addAttribute("newThreatCount", diff.newThreats());

        String diffAiInsight = resolveDiffAiInsight(project, fromScan, toScan, fromVersion, toVersion, diff);
        model.addAttribute("diffAiInsight", diffAiInsight);
    }

    private String resolveDiffAiInsight(Project project, ScanResult fromScan, ScanResult toScan,
                                        String fromVersion, String toVersion,
                                        ScanVersionDiffAnalyzer.DiffResult diff) {
        if (toScan.getVersionDiffAiInsight() != null
                && fromScan.getId().equals(toScan.getVersionDiffFromScanId())) {
            return toScan.getVersionDiffAiInsight();
        }
        try {
            return aiAnalysisService.summarizeVersionDiff(
                    project.getName(), fromVersion, toVersion,
                    diff.added(), diff.removed(), diff.updated(), diff.newThreats(),
                    diff.threatDetails());
        } catch (Exception e) {
            log.warn("[VersionDiff] AI diff summary failed for projectId={}: {}",
                    project.getId(), e.getMessage());
            return null;
        }
    }

    private String versionLabel(ScanResult s) {
        return s.getVersion() != null ? s.getVersion()
                : s.getScannedAt().toLocalDate().toString().replace("-", ".");
    }
}
