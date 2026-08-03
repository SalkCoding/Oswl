package com.salkcoding.oswl.service.reporting;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.service.ai.AiResponseSanitizer;
import com.salkcoding.oswl.util.VersionOrder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskTrendService {

    @Value("${oswl.risk-trend.limit:10}")
    private int trendLimit;

    private final ProjectRepository    projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final LibraryRepository    libraryRepository;

    /**
     * @param scanId the scan the user selected in the version dropdown; null means "latest".
     *               The trend is drawn up to and including that scan (older versions to its left),
     *               so picking an older version shows the project as it looked back then rather
     *               than silently re-rendering the latest scan.
     */
    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long scanId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        List<ScanResult> allScans = new ArrayList<>(scanResultRepository.findCompletedByProjectId(projectId));
        VersionOrder.sortDesc(allScans);

        ScanResult selected = allScans.stream()
                .filter(s -> s.getId().equals(scanId))
                .findFirst()
                .orElse(allScans.isEmpty() ? null : allScans.getFirst());
        Long activeScanId = selected != null ? selected.getId() : null;

        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion()
                                : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null
                                ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(s.getId().equals(activeScanId))
                        .build())
                .toList();
        model.addAttribute("scanVersions", scanVersions);
        model.addAttribute("currentScanId", activeScanId);

        // Trend window. Viewing the latest scan (the default) keeps the original indexed
        // "most recent N" query; only an explicit older selection needs the window re-sliced
        // out of the already-loaded full list so the chart ends at that version.
        List<ScanResult> scansDesc = new ArrayList<>();
        boolean viewingLatest = selected == null || selected.getId().equals(allScans.getFirst().getId());
        if (viewingLatest) {
            scansDesc.addAll(scanResultRepository.findRecentCompleted(projectId, trendLimit));
            VersionOrder.sortDesc(scansDesc);
        } else {
            boolean reached = false;
            for (ScanResult s : allScans) {
                if (!reached && !s.getId().equals(activeScanId)) continue;
                reached = true;
                scansDesc.add(s);
                if (scansDesc.size() >= Math.max(1, trendLimit)) break;
            }
        }

        if (scansDesc.isEmpty()) {
            addEmptyChartData(model);
            return;
        }

        ScanResult latest = scansDesc.getFirst();
        model.addAttribute("projectVersion", latest.getVersion() != null ? latest.getVersion() : "-");
        // Labels the insight card: "AI Insight (v1.0.1)" plus the date it was produced, so an
        // insight from an older version is never mistaken for the current one.
        model.addAttribute("insightVersion", latest.getVersion());
        model.addAttribute("insightGeneratedAt", latest.getScannedAt() != null
                ? latest.getScannedAt().toLocalDate().toString() : null);

        List<ScanResult> scansAsc = new ArrayList<>(scansDesc);
        Collections.reverse(scansAsc);

        List<String>  versions    = new ArrayList<>();
        List<Integer> secCritical = new ArrayList<>();
        List<Integer> secHigh     = new ArrayList<>();
        List<Integer> secMedium   = new ArrayList<>();
        List<Integer> secLow      = new ArrayList<>();
        List<Integer> secUnscored = new ArrayList<>();
        List<Integer> licRestricted = new ArrayList<>();
        List<Integer> licCaution     = new ArrayList<>();
        List<Integer> licUnknown     = new ArrayList<>();
        List<Integer> licPermitted   = new ArrayList<>();

        for (ScanResult scan : scansAsc) {
            List<Library> libs = libraryRepository.findByScanResultIdWithCves(scan.getId());
            int[] sec = aggregateSecurity(libs);
            int[] lic = aggregateLicense(libs);

            versions.add(scan.getVersion() != null ? scan.getVersion() : "?");
            secCritical.add(sec[0]);
            secHigh.add(sec[1]);
            secMedium.add(sec[2]);
            secLow.add(sec[3]);
            secUnscored.add(sec[4]);
            licRestricted.add(lic[0]);
            licCaution.add(lic[1]);
            licUnknown.add(lic[2]);
            licPermitted.add(lic[3]);
        }

        List<Library> latestLibs = libraryRepository.findByScanResultIdWithCves(latest.getId());
        int[] latestSec = aggregateSecurity(latestLibs);
        int[] latestLic = aggregateLicense(latestLibs);
        int currentSecIssues = latestSec[0] + latestSec[1] + latestSec[2] + latestSec[3] + latestSec[4];
        int currentLicIssues = latestLic[0] + latestLic[1] + latestLic[2] + latestLic[3];

        int secDelta = 0;
        int licDelta = 0;
        if (scansDesc.size() >= 2) {
            List<Library> prevLibs = libraryRepository.findByScanResultIdWithCves(scansDesc.get(1).getId());
            int[] prevSec = aggregateSecurity(prevLibs);
            int[] prevLic = aggregateLicense(prevLibs);
            secDelta = currentSecIssues - (prevSec[0] + prevSec[1] + prevSec[2] + prevSec[3] + prevSec[4]);
            licDelta = currentLicIssues - (prevLic[0] + prevLic[1] + prevLic[2] + prevLic[3]);
        }

        model.addAttribute("securityIssues", currentSecIssues);
        model.addAttribute("securityDelta",  secDelta);
        model.addAttribute("licenseIssues",  currentLicIssues);
        model.addAttribute("licenseDelta",   licDelta);

        model.addAttribute("securityAiInsight", AiResponseSanitizer.sanitizePlainText(latest.getSecurityAiInsight()));
        model.addAttribute("licenseAiInsight",  AiResponseSanitizer.sanitizePlainText(latest.getLicenseAiInsight()));

        model.addAttribute("chartVersions",    versions);
        model.addAttribute("chartSecCritical", secCritical);
        model.addAttribute("chartSecHigh",     secHigh);
        model.addAttribute("chartSecMedium",   secMedium);
        model.addAttribute("chartSecLow",      secLow);
        model.addAttribute("chartSecNone",     secUnscored);
        model.addAttribute("chartLicRestricted", licRestricted);
        model.addAttribute("chartLicCaution",     licCaution);
        model.addAttribute("chartLicUnknown",     licUnknown);
        model.addAttribute("chartLicPermitted",   licPermitted);
    }

    private int[] aggregateSecurity(List<Library> libraries) {
        int c = 0, h = 0, m = 0, l = 0, n = 0;
        for (Library lib : libraries) {
            for (var cve : lib.getCves()) {
                if (cve.getSeverity() == null) continue;
                switch (cve.getSeverity()) {
                    case CRITICAL -> c++;
                    case HIGH     -> h++;
                    case MEDIUM   -> m++;
                    case LOW      -> l++;
                    case NONE     -> n++;
                    default       -> {}
                }
            }
        }
        return new int[]{c, h, m, l, n};
    }

    private int[] aggregateLicense(List<Library> libraries) {
        int c = 0, h = 0, u = 0, l = 0;
        for (Library lib : libraries) {
            switch (lib.getLicenseStatus()) {
                case RESTRICTED -> c++;
                case CAUTION      -> h++;
                case UNKNOWN   -> u++;
                default        -> l++;
            }
        }
        return new int[]{c, h, u, l};
    }

    private void addEmptyChartData(Model model) {
        model.addAttribute("projectVersion",         "-");
        model.addAttribute("insightVersion",        null);
        model.addAttribute("insightGeneratedAt",    null);
        model.addAttribute("securityIssues",           0);
        model.addAttribute("securityDelta",            0);
        model.addAttribute("licenseIssues",            0);
        model.addAttribute("licenseDelta",             0);
        model.addAttribute("chartVersions",    List.of());
        model.addAttribute("chartSecCritical", List.of());
        model.addAttribute("chartSecHigh",     List.of());
        model.addAttribute("chartSecMedium",   List.of());
        model.addAttribute("chartSecLow",      List.of());
        model.addAttribute("chartLicRestricted", List.of());
        model.addAttribute("chartLicCaution",     List.of());
        model.addAttribute("chartLicUnknown",     List.of());
        model.addAttribute("chartLicPermitted",   List.of());
        model.addAttribute("chartSecNone",     List.of());
        model.addAttribute("securityAiInsight", null);
        model.addAttribute("licenseAiInsight",  null);
    }
}
