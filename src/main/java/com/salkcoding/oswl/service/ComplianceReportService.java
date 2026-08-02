package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.dto.ComplianceReportDto;
import com.salkcoding.oswl.dto.ComplianceReportDto.KevRow;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Assembles the compliance report (CRA readiness / ISMS-P evidence) from the project's
 * most recent completed scan. Pure read-model reassembly of existing scan, CVE, and
 * deferral data — no new entities or persistence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceReportService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final LibraryRepository libraryRepository;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Transactional(readOnly = true)
    public ComplianceReportDto build(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        String generatedAt = LocalDateTime.now().format(TS);

        ScanResult scan = scanResultRepository.findRecentCompleted(projectId, 1).stream()
                .findFirst().orElse(null);
        if (scan == null) {
            return emptyReport(project.getName(), generatedAt);
        }

        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());
        Map<Long, ScanComponent> scByLibrary = new java.util.HashMap<>();
        for (ScanComponent sc : components) {
            scByLibrary.putIfAbsent(sc.getLibrary().getId(), sc);
        }

        int criticalCves = 0, highCves = 0, mediumCves = 0, lowCves = 0, unscoredCves = 0;
        int kevTotal = 0, kevUnresolved = 0;
        int licenseViolations = 0, licenseWarnings = 0, licenseUnknown = 0, licensePermitted = 0;
        List<KevRow> kevRows = new ArrayList<>();

        for (Library lib : libraries) {
            ScanComponent sc = scByLibrary.get(lib.getId());
            boolean triaged = sc != null && (sc.isDeferred() || sc.isReviewed());

            for (Cve cve : lib.getCves()) {
                if (cve.getSeverity() != null) {
                    switch (cve.getSeverity()) {
                        case CRITICAL -> criticalCves++;
                        case HIGH -> highCves++;
                        case MEDIUM -> mediumCves++;
                        case LOW -> lowCves++;
                        case NONE -> unscoredCves++;
                        default -> { }
                    }
                } else {
                    unscoredCves++;
                }
                if (Boolean.TRUE.equals(cve.getKevListed())) {
                    kevTotal++;
                    if (!triaged) kevUnresolved++;
                    kevRows.add(new KevRow(
                            cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId(),
                            lib.getName(),
                            lib.getVersion() != null ? lib.getVersion() : "-",
                            cve.getSeverity() != null ? cve.getSeverity().name() : "UNSCORED",
                            cve.getFixVersion() != null ? cve.getFixVersion() : "—",
                            triageState(sc)));
                }
            }

            switch (lib.getLicenseStatus()) {
                case RESTRICTED -> licenseViolations++;
                case CAUTION -> licenseWarnings++;
                case UNKNOWN -> licenseUnknown++;
                default -> licensePermitted++;
            }
        }

        // Triage SLA metrics
        int reviewed = 0, deferred = 0, untriagedRisk = 0;
        List<Duration> triageDurations = new ArrayList<>();
        for (ScanComponent sc : components) {
            boolean hasRisk = sc.getLibrary().getCves().stream()
                    .anyMatch(c -> c.getSeverity() != null
                            && c.getSeverity() != com.salkcoding.oswl.domain.enums.RiskLevel.NONE);
            if (sc.isDeferred()) {
                deferred++;
                if (sc.getDeferredAt() != null && scan.getScannedAt() != null) {
                    Duration d = Duration.between(scan.getScannedAt(), sc.getDeferredAt());
                    if (!d.isNegative()) triageDurations.add(d);
                }
            } else if (sc.isReviewed()) {
                reviewed++;
            } else if (hasRisk) {
                untriagedRisk++;
            }
        }

        // Highest-severity KEV rows first
        kevRows.sort(Comparator.comparingInt(ComplianceReportService::severityRank));

        return new ComplianceReportDto(
                project.getName(),
                generatedAt,
                true,
                scan.getVersion() != null ? scan.getVersion() : "-",
                scan.getScannedAt() != null ? scan.getScannedAt().format(DATE) : "-",
                components.size(),
                kevTotal,
                kevUnresolved,
                criticalCves, highCves, mediumCves, lowCves, unscoredCves,
                reviewed, deferred, untriagedRisk,
                formatAvgTriage(triageDurations),
                licenseViolations, licenseWarnings, licenseUnknown, licensePermitted,
                kevRows);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static int severityRank(KevRow row) {
        return switch (row.severity()) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private String triageState(ScanComponent sc) {
        if (sc == null) return "untriaged";
        if (sc.isDeferred()) {
            return "deferred" + (sc.getDeferralReason() != null ? " (" + sc.getDeferralReason() + ")" : "");
        }
        if (sc.isReviewed()) return "reviewed";
        return "untriaged";
    }

    private String formatAvgTriage(List<Duration> durations) {
        if (durations.isEmpty()) return "—";
        long avgSeconds = durations.stream().mapToLong(Duration::getSeconds).sum() / durations.size();
        long days = avgSeconds / 86_400;
        long hours = (avgSeconds % 86_400) / 3_600;
        if (days > 0) {
            return hours > 0 ? days + "d " + hours + "h" : days + "d";
        }
        long minutes = (avgSeconds % 3_600) / 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private ComplianceReportDto emptyReport(String projectName, String generatedAt) {
        return new ComplianceReportDto(
                projectName, generatedAt, false,
                "-", "-", 0,
                0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, "—",
                0, 0, 0, 0,
                List.of());
    }
}
