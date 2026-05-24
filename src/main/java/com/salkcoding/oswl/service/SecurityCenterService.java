package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.dto.BulkStatusRequest;
import com.salkcoding.oswl.dto.ComponentRowDto;
import com.salkcoding.oswl.dto.VersionSummaryDto;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityCenterService {

    private final ProjectRepository       projectRepository;
    private final ScanResultRepository    scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final LibraryRepository       libraryRepository;
    private final AuditLogService         auditLogService;

    @Transactional(readOnly = true)
    public void populateModel(Long projectId, Long scanId, Model model) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        model.addAttribute("projectId", projectId);
        model.addAttribute("projectName", project.getName());

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);

        ScanResult scan;
        if (scanId != null) {
            scan = allScans.stream()
                    .filter(s -> s.getId().equals(scanId))
                    .findFirst()
                    .orElse(allScans.isEmpty() ? null : allScans.get(0));
        } else {
            scan = allScans.isEmpty() ? null : allScans.get(0);
        }

        Long activeScanId = scan != null ? scan.getId() : null;

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

        // Add the latest scan's status and ID for the UI polling banner
        ScanResult latestAny = scanResultRepository.findLatestByProjectId(projectId).orElse(null);
        model.addAttribute("latestScanId",     latestAny != null ? latestAny.getId() : null);
        model.addAttribute("latestScanStatus", latestAny != null ? latestAny.getStatus().name() : "NONE");

        if (scan == null) {
            addEmptySummary(model);
            return;
        }

        model.addAttribute("projectVersion", scan.getVersion() != null ? scan.getVersion() : "-");
        model.addAttribute("scannedAt", scan.getScannedAt() != null
                ? scan.getScannedAt().toLocalDate().toString() : "-");

        // Update banner: show when viewing an older scan and a newer completed scan exists
        ScanResult latestCompleted = allScans.get(0);
        if (!scan.getId().equals(latestCompleted.getId())) {
            String fromVer = scan.getVersion() != null ? scan.getVersion()
                    : scan.getScannedAt().toLocalDate().toString().replace("-", ".");
            String toVer = latestCompleted.getVersion() != null ? latestCompleted.getVersion()
                    : latestCompleted.getScannedAt().toLocalDate().toString().replace("-", ".");
            model.addAttribute("updateFromVersion", fromVer);
            model.addAttribute("updateToVersion", toVer);
            model.addAttribute("updateToScanId", latestCompleted.getId());
        }

        // Eagerly load libraries + CVEs via the join query
        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());
        // Map library.id → ScanComponent for reviewed/ignored flags
        List<ScanComponent> scanComponents = scanComponentRepository.findByScanResultId(scan.getId());
        java.util.Map<Long, ScanComponent> libToSc = new java.util.HashMap<>();
        for (ScanComponent sc : scanComponents) {
            libToSc.put(sc.getLibrary().getId(), sc);
        }

        // Batch-compute how many distinct projects reference each library across all completed scans
        List<Long> libraryIds = libraries.stream().map(Library::getId).toList();
        java.util.Map<Long, Long> projectCounts = new java.util.HashMap<>();
        if (!libraryIds.isEmpty()) {
            for (Object[] row : scanComponentRepository.countDistinctProjectsByLibraryIds(libraryIds)) {
                projectCounts.put((Long) row[0], (Long) row[1]);
            }
        }

        int secCritical = 0, secHigh = 0, secMedium = 0, secLow = 0, secUnscored = 0;
        int licCritical = 0, licHigh = 0, licMedium = 0, licLow = 0;
        List<ComponentRowDto> rows = new ArrayList<>();

        for (Library lib : libraries) {
            int c = (int) lib.countBySeverity("CRITICAL");
            int h = (int) lib.countBySeverity("HIGH");
            int m = (int) lib.countBySeverity("MEDIUM");
            int l = (int) lib.countBySeverity("LOW");
            int n = (int) lib.countBySeverity("NONE");

            secCritical += c;
            secHigh     += h;
            secMedium   += m;
            secLow      += l;
            secUnscored += n;

            switch (lib.getLicenseStatus()) {
                case RESTRICTED -> licCritical++;
                case CAUTION      -> licHigh++;
                case UNKNOWN   -> licMedium++;
                default        -> licLow++;
            }

            ScanComponent sc = libToSc.get(lib.getId());
            boolean reviewed = sc != null && sc.isReviewed();
            boolean ignored  = sc != null && sc.isIgnored();
            boolean deferred = sc != null && sc.isDeferred();
            Long componentId = sc != null ? sc.getId() : null;
            String reviewedByName = sc != null ? sc.getReviewedByName() : null;
            String deferredByName = sc != null ? sc.getDeferredByName() : null;

            // Build dependencyInfo: stored string + "· Projects (N)"
            String depInfo = sc != null ? sc.getDependencyInfo() : null;
            long projCount = projectCounts.getOrDefault(lib.getId(), 0L);
            if (depInfo != null && projCount > 0) {
                depInfo = depInfo + " · Projects (" + projCount + ")";
            } else if (depInfo == null && projCount > 0) {
                depInfo = "Projects (" + projCount + ")";
            }

            rows.add(ComponentRowDto.builder()
                    .id(componentId)
                    .name(lib.getName())
                    .version(lib.getVersion())
                    .dependencyInfo(depInfo)
                    .reviewed(reviewed)
                    .reviewedByName(reviewedByName)
                    .ignored(ignored)
                    .deferred(deferred)
                    .deferredByName(deferredByName)
                    .securityCritical(c)
                    .securityHigh(h)
                    .securityMedium(m)
                    .securityLow(l)
                    .securityUnscored(n)
                    .patchability(patchabilityLabel(lib.computePatchability()))
                    .licenseStatus(lib.getLicenseStatus().name())
                    .licenseName(lib.getLicenseName())
                    .isLatestVersion(lib.getIsLatestVersion())
                    .deprecated(lib.getDeprecated())
                    .latestVersion(lib.getLatestVersion())
                    .build());
        }

        model.addAttribute("securityCritical", secCritical);
        model.addAttribute("securityHigh", secHigh);
        model.addAttribute("securityMedium", secMedium);
        model.addAttribute("securityLow", secLow);
        model.addAttribute("securityUnscored", secUnscored);
        model.addAttribute("licenseCritical", licCritical);
        model.addAttribute("licenseHigh", licHigh);
        model.addAttribute("licenseMedium", licMedium);
        model.addAttribute("licenseLow", licLow);
        model.addAttribute("components", rows);
        model.addAttribute("securityPostureInsight", scan.getSecurityPostureInsight());
    }

    @Transactional
    public void bulkUpdateStatus(Long projectId, BulkStatusRequest req) {
        String reviewerName = resolveCurrentDisplayName();
        List<ScanComponent> comps = scanComponentRepository.findAllByIdInAndProjectId(req.ids(), projectId);
        for (ScanComponent sc : comps) {
            if (req.reviewed() != null) sc.markReviewedBy(req.reviewed(), reviewerName);
            if (req.ignored()  != null) sc.markIgnored(req.ignored());
        }
        log.info("[SecurityCenter] bulkUpdateStatus projectId={} ids={} reviewed={} ignored={}",
                projectId, req.ids(), req.reviewed(), req.ignored());
        auditLogService.log("COMPONENT.BULK_STATUS_UPDATE", "COMPONENT",
                projectId.toString(),
                "[" + req.ids().size() + " components]",
                "ids=" + req.ids() + " reviewed=" + req.reviewed() + " ignored=" + req.ignored());
    }

    /** Returns the display name of the currently authenticated user, or "unknown" if unavailable. */
    private String resolveCurrentDisplayName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OswlUserPrincipal p) {
            return p.getDisplayName();
        }
        return auth != null ? auth.getName() : "unknown";
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void addEmptySummary(Model model) {
        model.addAttribute("projectVersion", "-");
        model.addAttribute("latestScanId",     null);
        model.addAttribute("latestScanStatus", "NONE");
        model.addAttribute("securityCritical", 0);
        model.addAttribute("securityHigh", 0);
        model.addAttribute("securityMedium", 0);
        model.addAttribute("securityLow", 0);
        model.addAttribute("licenseCritical", 0);
        model.addAttribute("licenseHigh", 0);
        model.addAttribute("licenseMedium", 0);
        model.addAttribute("licenseLow", 0);
        model.addAttribute("components", List.of());
    }

    private String patchabilityLabel(Patchability p) {
        return switch (p) {
            case PATCHABLE     -> "patchable";
            case NON_PATCHABLE -> "non-patchable";
            default            -> "unknown";
        };
    }

    /**
     * Builds a CSV byte array for the security-center export.
     * If scanId is provided, exports that specific scan; otherwise uses the latest completed scan.
     */
    @Transactional(readOnly = true)
    public byte[] buildExportCsv(Long projectId, Long scanId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        List<ScanResult> allScans = scanResultRepository.findCompletedByProjectId(projectId);
        if (allScans.isEmpty()) {
            return csvHeader().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        ScanResult scan = (scanId != null)
                ? allScans.stream().filter(s -> s.getId().equals(scanId)).findFirst().orElse(allScans.getFirst())
                : allScans.getFirst();

        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());

        var sb = new StringBuilder();
        sb.append(csvHeader()).append("\n");

        for (ScanComponent sc : components) {
            Library lib = sc.getLibrary();
            int c = (int) lib.getCves().stream().filter(v -> v.getSeverity() != null && v.getSeverity().name().equals("CRITICAL")).count();
            int h = (int) lib.getCves().stream().filter(v -> v.getSeverity() != null && v.getSeverity().name().equals("HIGH")).count();
            int m = (int) lib.getCves().stream().filter(v -> v.getSeverity() != null && v.getSeverity().name().equals("MEDIUM")).count();
            int l = (int) lib.getCves().stream().filter(v -> v.getSeverity() != null && v.getSeverity().name().equals("LOW")).count();
            int n = (int) lib.getCves().stream().filter(v -> v.getSeverity() == null || v.getSeverity().name().equals("UNSCORED")).count();

            sb.append(csvEscape(lib.getName())).append(',')
              .append(csvEscape(lib.getVersion() != null ? lib.getVersion() : "")).append(',')
              .append(csvEscape(lib.getEcosystem() != null ? lib.getEcosystem() : "")).append(',')
              .append(c).append(',').append(h).append(',').append(m).append(',').append(l).append(',').append(n).append(',')
              .append(csvEscape(patchabilityLabel(lib.computePatchability()))).append(',')
              .append(csvEscape(lib.getLicenseName() != null ? lib.getLicenseName() : "")).append(',')
              .append(csvEscape(lib.getLicenseStatus().name())).append(',')
              .append(sc.isReviewed() ? "Yes" : "No").append(',')
              .append(sc.isIgnored()  ? "Yes" : "No").append(',')
              .append(sc.isDeferred() ? "Yes" : "No").append(',')
              .append(csvEscape(sc.getDeferralReason() != null ? sc.getDeferralReason() : ""))
              .append("\n");
        }

        auditLogService.log("SECURITY_CENTER.EXPORT", "PROJECT",
                projectId.toString(), project.getName(),
                "format=csv, scanId=" + (scanId != null ? scanId : scan.getId()));

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String csvHeader() {
        return "Component Name,Version,Ecosystem,Critical CVEs,High CVEs,Medium CVEs,Low CVEs,Unscored CVEs,Patchability,License,License Status,Reviewed,Ignored,Deferred,Deferral Reason";
    }

    private String csvEscape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}

