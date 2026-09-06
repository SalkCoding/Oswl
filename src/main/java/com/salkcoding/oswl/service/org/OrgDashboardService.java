package com.salkcoding.oswl.service.org;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.dto.OrgProjectRiskDto;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Organization-wide portfolio roll-up for the admin dashboard.
 * Reuses the per-scan aggregation patterns of {@link SecurityCenterService} /
 * {@link RiskTrendService}: severity counts from CVE severities, license buckets from
 * {@code Library.licenseStatus}, and KEV "unaddressed" = KEV-listed CVE on a component
 * that is neither reviewed nor deferred (same triage rule as the compliance report).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgDashboardService {

    @Value("${oswl.org-dashboard.trend-weeks:10}")
    private int trendWeeks;

    private final ProjectRepository       projectRepository;
    private final ScanResultRepository    scanResultRepository;
    private final LibraryRepository       libraryRepository;
    private final com.salkcoding.oswl.service.scan.ScanSummaryReader summaryReader;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    @Transactional(readOnly = true)
    public void populateModel(Model model) {
        List<Project> projects = projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        // All completed scans of every project in one query, grouped per project (scannedAt
        // DESC within each project) — the trend walk needs the full per-project history, not
        // just the latest scan, so this is a real batch rather than a latest-per-project query.
        Map<Long, List<ScanResult>> completedByProject = new HashMap<>();
        for (Project project : projects) {
            completedByProject.put(project.getId(), new ArrayList<>());
        }
        if (!projects.isEmpty()) {
            List<Long> projectIds = projects.stream().map(Project::getId).toList();
            for (ScanResult scan : scanResultRepository.findCompletedByProjectIdIn(projectIds)) {
                completedByProject.get(scan.getProject().getId()).add(scan);
            }
        }

        // Latest completed scan per project (null when the project was never scanned)
        Map<Long, ScanResult> latestScanByProject = new HashMap<>();
        for (Project project : projects) {
            List<ScanResult> completed = completedByProject.get(project.getId());
            if (!completed.isEmpty()) {
                latestScanByProject.put(project.getId(), completed.getFirst());
            }
        }

        // Per-project scans in ascending order for the trend's as-of walk
        Map<Long, List<ScanResult>> scansAscByProject = new HashMap<>();
        for (Project project : projects) {
            List<ScanResult> asc = new ArrayList<>(completedByProject.get(project.getId()));
            asc.sort(Comparator.comparing(ScanResult::getScannedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            scansAscByProject.put(project.getId(), asc);
        }

        // Aggregate only scans referenced by the latest posture or a weekly trend bucket.
        Set<Long> aggregateScanIds = new HashSet<>();
        latestScanByProject.values().forEach(scan -> aggregateScanIds.add(scan.getId()));
        LocalDate today = LocalDate.now();
        for (int i = trendWeeks - 1; i >= 0; i--) {
            LocalDateTime bucketEndExclusive = today.minusWeeks(i).plusDays(1).atStartOfDay();
            for (Project project : projects) {
                ScanResult asOf = latestScanAtOrBefore(scansAscByProject.get(project.getId()),
                        bucketEndExclusive);
                if (asOf != null) aggregateScanIds.add(asOf.getId());
            }
        }
        List<ScanResult> selectedScans = completedByProject.values().stream().flatMap(List::stream)
                .filter(scan -> aggregateScanIds.contains(scan.getId())).toList();
        Map<Long, Posture> postureByScanId = loadPosture(selectedScans);

        // ── Org-wide posture totals + worst-project ranking ──────────────
        int secCritical = 0, secHigh = 0, secMedium = 0, secLow = 0, secUnscored = 0;
        int licViolations = 0, licWarnings = 0, licUnknown = 0, licPermitted = 0;
        int kevTotal = 0, kevUnaddressed = 0;
        List<OrgProjectRiskDto> rows = new ArrayList<>();

        for (Project project : projects) {
            ScanResult latest = latestScanByProject.get(project.getId());
            if (latest == null) {
                rows.add(OrgProjectRiskDto.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .scanned(false)
                        .build());
                continue;
            }

            Posture posture = postureByScanId.getOrDefault(latest.getId(), new Posture());
            int[] sec = posture.security;
            int[] lic = posture.licenses;
            int[] kev = posture.kev;

            secCritical += sec[0]; secHigh   += sec[1]; secMedium += sec[2];
            secLow      += sec[3]; secUnscored += sec[4];
            licViolations += lic[0]; licWarnings += lic[1];
            licUnknown    += lic[2]; licPermitted += lic[3];
            kevTotal += kev[0]; kevUnaddressed += kev[1];

            rows.add(OrgProjectRiskDto.builder()
                    .id(project.getId())
                    .name(project.getName())
                    .version(latest.getVersion() != null ? latest.getVersion() : "-")
                    .lastScanned(latest.getScannedAt() != null
                            ? latest.getScannedAt().toLocalDate().format(DATE) : "-")
                    .scanned(true)
                    .securityCritical(sec[0]).securityHigh(sec[1])
                    .securityMedium(sec[2]).securityLow(sec[3]).securityUnscored(sec[4])
                    .licenseViolations(lic[0]).licenseWarnings(lic[1])
                    .kevUnaddressed(kev[1])
                    .build());
        }

        rows.sort(Comparator
                .comparingInt(OrgProjectRiskDto::getSecurityCritical)
                .thenComparingInt(OrgProjectRiskDto::getSecurityHigh)
                .thenComparingInt(OrgProjectRiskDto::getSecurityMedium)
                .thenComparingInt(OrgProjectRiskDto::getSecurityLow)
                .thenComparingInt(OrgProjectRiskDto::getSecurityUnscored)
                .reversed()
                .thenComparing(OrgProjectRiskDto::getName, String.CASE_INSENSITIVE_ORDER));

        model.addAttribute("totalProjects", projects.size());
        model.addAttribute("scannedProjects", latestScanByProject.size());
        model.addAttribute("securityCritical", secCritical);
        model.addAttribute("securityHigh", secHigh);
        model.addAttribute("securityMedium", secMedium);
        model.addAttribute("securityLow", secLow);
        model.addAttribute("securityUnscored", secUnscored);
        model.addAttribute("totalVulnerabilities",
                secCritical + secHigh + secMedium + secLow + secUnscored);
        model.addAttribute("licenseCritical", licViolations);
        model.addAttribute("licenseHigh", licWarnings);
        model.addAttribute("licenseMedium", licUnknown);
        model.addAttribute("licenseLow", licPermitted);
        model.addAttribute("licenseViolations", licViolations);
        model.addAttribute("kevTotal", kevTotal);
        model.addAttribute("kevUnaddressed", kevUnaddressed);
        model.addAttribute("projectRows", rows);

        addTrendModel(model, projects, scansAscByProject, postureByScanId);
    }

    private static final class Posture {
        final int[] security = new int[5];
        final int[] licenses = new int[4];
        final int[] kev = new int[2];
    }

    private Map<Long, Posture> loadPosture(List<ScanResult> scans) {
        Map<Long, Posture> result = new HashMap<>();
        if (scans.isEmpty()) return result;
        summaryReader.read(scans).forEach((id, summary) -> {
            Posture posture = new Posture();
            System.arraycopy(summary.security(), 0, posture.security, 0, 5);
            System.arraycopy(summary.licenses(), 0, posture.licenses, 0, 4);
            result.put(id, posture);
        });
        List<Long> scanIds = scans.stream().filter(scan -> !scan.isArchived()).map(ScanResult::getId).toList();
        if (scanIds.isEmpty()) return result;
        for (Object[] row : libraryRepository.countPortfolioKev(scanIds)) {
            result.get((Long) row[0]).kev[0] = Math.toIntExact(((Number) row[1]).longValue());
            result.get((Long) row[0]).kev[1] = Math.toIntExact(((Number) row[2]).longValue());
        }
        return result;
    }

    // ── Org-wide vulnerability trend (weekly buckets) ────────────────────

    /**
     * For each of the last {@code trendWeeks} weekly buckets, sums the severity counts of
     * every project's latest completed scan as of the bucket end date (posture as-of date).
     * Same severity bucketing as {@link RiskTrendService}.
     */
    private void addTrendModel(Model model, List<Project> projects,
                               Map<Long, List<ScanResult>> scansAscByProject,
                               Map<Long, Posture> postureByScanId) {
        List<String>  labels      = new ArrayList<>();
        List<Integer> trCritical  = new ArrayList<>();
        List<Integer> trHigh      = new ArrayList<>();
        List<Integer> trMedium    = new ArrayList<>();
        List<Integer> trLow       = new ArrayList<>();
        List<Integer> trUnscored  = new ArrayList<>();

        // Severity counts are computed once per distinct scan actually used by a bucket
        Map<Long, int[]> secCountsByScanId = new HashMap<>();

        LocalDate today = LocalDate.now();
        for (int i = trendWeeks - 1; i >= 0; i--) {
            LocalDate bucketEnd = today.minusWeeks(i);
            LocalDateTime bucketEndExclusive = bucketEnd.plusDays(1).atStartOfDay();
            labels.add(bucketEnd.format(DATE));

            int c = 0, h = 0, m = 0, l = 0, n = 0;
            for (Project project : projects) {
                ScanResult asOf = latestScanAtOrBefore(scansAscByProject.get(project.getId()),
                        bucketEndExclusive);
                if (asOf == null) continue;
                int[] sec = secCountsByScanId.computeIfAbsent(asOf.getId(),
                        scanId -> postureByScanId.getOrDefault(scanId, new Posture()).security);
                c += sec[0]; h += sec[1]; m += sec[2]; l += sec[3]; n += sec[4];
            }
            trCritical.add(c); trHigh.add(h); trMedium.add(m); trLow.add(l); trUnscored.add(n);
        }

        model.addAttribute("chartLabels",     labels);
        model.addAttribute("chartSecCritical", trCritical);
        model.addAttribute("chartSecHigh",     trHigh);
        model.addAttribute("chartSecMedium",   trMedium);
        model.addAttribute("chartSecLow",      trLow);
        model.addAttribute("chartSecNone",     trUnscored);
    }

    /** Latest scan with scannedAt before the given exclusive bound; scans must be ascending. */
    private ScanResult latestScanAtOrBefore(List<ScanResult> scansAsc, LocalDateTime boundExclusive) {
        ScanResult chosen = null;
        for (ScanResult scan : scansAsc) {
            if (scan.getScannedAt() == null || !scan.getScannedAt().isBefore(boundExclusive)) break;
            chosen = scan;
        }
        return chosen;
    }

}
