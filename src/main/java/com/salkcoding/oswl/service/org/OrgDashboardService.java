package com.salkcoding.oswl.service.org;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
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

        // Every scan the page aggregates: each project's latest scan plus every scan the trend
        // walk can reference as-of a weekly bucket boundary. Batch-loading their components,
        // libraries, and CVEs up front (2 queries total, same hydration pattern as
        // ProjectService.findAll) keeps the aggregations below from issuing per-scan component
        // queries and per-CVE Cve.sources selects.
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
        Map<Long, List<ScanComponent>> componentsByScanId = loadComponentsByScanId(aggregateScanIds);

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

            List<ScanComponent> components = componentsByScanId.getOrDefault(latest.getId(), List.of());
            int[] sec = aggregateSecurity(components);
            int[] lic = aggregateLicense(components);
            int[] kev = aggregateKev(components);

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

        addTrendModel(model, projects, scansAscByProject, componentsByScanId);
    }

    /**
     * Batch-loads the components (each with its EAGER library) of every scan the page aggregates,
     * then hydrates those libraries' CVEs — and each CVE's EAGER {@code sources} collection — in
     * one more query. Mirrors {@code ProjectService.findAll}'s hydration: the second query's
     * return value isn't needed, it initializes collections on the already persistence-context-
     * loaded Library entities.
     */
    private Map<Long, List<ScanComponent>> loadComponentsByScanId(Set<Long> scanIds) {
        if (scanIds.isEmpty()) return Map.of();
        Map<Long, List<ScanComponent>> byScanId = new HashMap<>();
        for (ScanResult scan : scanResultRepository.findByIdInWithComponentsAndLibrary(scanIds)) {
            byScanId.put(scan.getId(), scan.getComponents());
        }
        libraryRepository.findByScanResultIdInWithCves(scanIds);
        return byScanId;
    }

    // ── Org-wide vulnerability trend (weekly buckets) ────────────────────

    /**
     * For each of the last {@code trendWeeks} weekly buckets, sums the severity counts of
     * every project's latest completed scan as of the bucket end date (posture as-of date).
     * Same severity bucketing as {@link RiskTrendService}.
     */
    private void addTrendModel(Model model, List<Project> projects,
                               Map<Long, List<ScanResult>> scansAscByProject,
                               Map<Long, List<ScanComponent>> componentsByScanId) {
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
                        scanId -> aggregateSecurity(componentsByScanId.getOrDefault(scanId, List.of())));
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

    // ── Aggregations (mirrors RiskTrendService / ComplianceReportService) ─

    private int[] aggregateSecurity(List<ScanComponent> components) {
        int c = 0, h = 0, m = 0, l = 0, n = 0;
        for (ScanComponent sc : components) {
            for (Cve cve : sc.getLibrary().getCves()) {
                if (cve.getSeverity() == null) { n++; continue; }
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

    private int[] aggregateLicense(List<ScanComponent> components) {
        int violations = 0, warnings = 0, unknown = 0, permitted = 0;
        for (ScanComponent sc : components) {
            switch (sc.getLibrary().getLicenseStatus()) {
                case RESTRICTED -> violations++;
                case CAUTION    -> warnings++;
                case UNKNOWN    -> unknown++;
                default         -> permitted++;
            }
        }
        return new int[]{violations, warnings, unknown, permitted};
    }

    /** [0] = total KEV-listed CVEs, [1] = KEV-listed CVEs on un-triaged (not reviewed/deferred) components. */
    private int[] aggregateKev(List<ScanComponent> components) {
        int total = 0, unaddressed = 0;
        for (ScanComponent sc : components) {
            boolean triaged = sc.isDeferred() || sc.isReviewed();
            for (Cve cve : sc.getLibrary().getCves()) {
                if (Boolean.TRUE.equals(cve.getKevListed())) {
                    total++;
                    if (!triaged) unaddressed++;
                }
            }
        }
        return new int[]{total, unaddressed};
    }
}
