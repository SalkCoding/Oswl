package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RiskTrendService {

    private static final int TREND_LIMIT = 10;

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        // Recent completed scans (DESC order → reversed for chart display)
        List<ScanResult> scansDesc = scanResultRepository.findRecentCompleted(projectId, TREND_LIMIT);

        // All completed scans for the topbar version dropdown
        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);
        List<VersionSummaryDto> scanVersions = allScans.stream()
                .map(s -> VersionSummaryDto.builder()
                        .scanId(s.getId())
                        .version(s.getVersion() != null ? s.getVersion() : s.getScannedAt().toLocalDate().toString().replace("-", "."))
                        .scannedAt(s.getScannedAt() != null ? s.getScannedAt().toLocalDate().toString().replace("-", ".") : "-")
                        .current(false)
                        .build())
                .toList();
        model.addAttribute("scanVersions", scanVersions);
        model.addAttribute("currentScanId", (Object) null);

        if (scansDesc.isEmpty()) {
            model.addAttribute("projectVersion", "-");
            model.addAttribute("securityIssues", 0);
            model.addAttribute("securityDelta", 0);
            model.addAttribute("licenseIssues", 0);
            model.addAttribute("licenseDelta", 0);
            model.addAttribute("chartVersions", List.of());
            model.addAttribute("chartSecCritical", List.of());
            model.addAttribute("chartSecHigh",     List.of());
            model.addAttribute("chartSecMedium",   List.of());
            model.addAttribute("chartSecLow",      List.of());
            model.addAttribute("chartLicCritical", List.of());
            model.addAttribute("chartLicHigh",     List.of());
            model.addAttribute("chartLicMedium",   List.of());
            model.addAttribute("chartLicLow",      List.of());
            return;
        }

        // Current version based on the latest scan
        ScanResult latest = scansDesc.get(0);
        model.addAttribute("projectVersion", latest.getVersion() != null ? latest.getVersion() : "-");

        // Build chart data (oldest → newest)
        List<ScanResult> scansAsc = new ArrayList<>(scansDesc);
        Collections.reverse(scansAsc);

        List<String>  versions    = new ArrayList<>();
        List<Integer> secCritical = new ArrayList<>();
        List<Integer> secHigh     = new ArrayList<>();
        List<Integer> secMedium   = new ArrayList<>();
        List<Integer> secLow      = new ArrayList<>();
        List<Integer> licCritical = new ArrayList<>();
        List<Integer> licHigh     = new ArrayList<>();
        List<Integer> licMedium   = new ArrayList<>();
        List<Integer> licLow      = new ArrayList<>();

        for (ScanResult scan : scansAsc) {
            int[] sec = aggregateSecurity(scan);
            int[] lic = aggregateLicense(scan);

            versions.add(scan.getVersion() != null ? scan.getVersion() : "?");
            secCritical.add(sec[0]);
            secHigh.add(sec[1]);
            secMedium.add(sec[2]);
            secLow.add(sec[3]);
            licCritical.add(lic[0]);
            licHigh.add(lic[1]);
            licMedium.add(lic[2]);
            licLow.add(lic[3]);
        }

        // Current issue counts (latest scan)
        int[] latestSec = aggregateSecurity(latest);
        int[] latestLic = aggregateLicense(latest);
        int currentSecIssues = latestSec[0] + latestSec[1] + latestSec[2] + latestSec[3];
        int currentLicIssues = latestLic[0] + latestLic[1] + latestLic[2] + latestLic[3];

        // Delta compared to previous scan
        int secDelta = 0;
        int licDelta = 0;
        if (scansDesc.size() >= 2) {
            ScanResult prev = scansDesc.get(1);
            int[] prevSec = aggregateSecurity(prev);
            int[] prevLic = aggregateLicense(prev);
            int prevSecIssues = prevSec[0] + prevSec[1] + prevSec[2] + prevSec[3];
            int prevLicIssues = prevLic[0] + prevLic[1] + prevLic[2] + prevLic[3];
            secDelta = currentSecIssues - prevSecIssues;
            licDelta = currentLicIssues - prevLicIssues;
        }

        model.addAttribute("securityIssues", currentSecIssues);
        model.addAttribute("securityDelta",  secDelta);
        model.addAttribute("licenseIssues",  currentLicIssues);
        model.addAttribute("licenseDelta",   licDelta);

        model.addAttribute("chartVersions",    versions);
        model.addAttribute("chartSecCritical", secCritical);
        model.addAttribute("chartSecHigh",     secHigh);
        model.addAttribute("chartSecMedium",   secMedium);
        model.addAttribute("chartSecLow",      secLow);
        model.addAttribute("chartLicCritical", licCritical);
        model.addAttribute("chartLicHigh",     licHigh);
        model.addAttribute("chartLicMedium",   licMedium);
        model.addAttribute("chartLicLow",      licLow);
    }

    private int[] aggregateSecurity(ScanResult scan) {
        int c = 0, h = 0, m = 0, l = 0;
        for (OswlComponent comp : scan.getComponents()) {
            for (var cve : comp.getCves()) {
                switch (cve.getSeverity()) {
                    case CRITICAL -> c++;
                    case HIGH     -> h++;
                    case MEDIUM   -> m++;
                    case LOW      -> l++;
                    default       -> {}
                }
            }
        }
        return new int[]{c, h, m, l};
    }

    private int[] aggregateLicense(ScanResult scan) {
        int c = 0, h = 0, m = 0, l = 0;
        for (OswlComponent comp : scan.getComponents()) {
            switch (comp.getLicenseStatus()) {
                case VIOLATION -> c++;
                case WARN      -> h++;
                default        -> l++;
            }
        }
        return new int[]{c, h, m, l};
    }
}
