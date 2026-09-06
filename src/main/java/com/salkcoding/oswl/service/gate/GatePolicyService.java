package com.salkcoding.oswl.service.gate;
import com.salkcoding.oswl.service.policy.PolicyService;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;
import com.salkcoding.oswl.domain.enums.Reachability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.gate.GateResultDto;
import com.salkcoding.oswl.dto.gate.GateResultDto.Thresholds;
import com.salkcoding.oswl.dto.gate.GateResultDto.Violation;
import com.salkcoding.oswl.dto.gate.GateResultDto.Coverage;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.domain.entity.scan.ScanFinding;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanFindingRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Evaluates a scan against configurable security-gate thresholds for CI / PR gating.
 *
 * Reuses the existing enrichment data (severity, KEV, EPSS, license policy status) and
 * the previous completed scan as the baseline for "new vulnerability" detection — no new
 * scanning or persistence. Deferred/ignored components are treated as accepted exceptions
 * and never fail the gate, and so are findings covered by an approved, unexpired
 * {@link PolicyException}.
 *
 * Threshold resolution order is request override → {@link PolicyService} org/team/project
 * policy hierarchy → {@code oswl.gate.*} instance defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatePolicyService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final ScanFindingRepository scanFindingRepository;
    private final LibraryRepository libraryRepository;
    private final PolicyService policyService;
    /** Null in plain-Mockito unit tests (no Spring context) — every use is guarded. */
    private final OswlMetrics oswlMetrics;

    @Value("${oswl.gate.fail-on-severity:HIGH}")
    private String defaultFailOnSeverity;
    @Value("${oswl.gate.fail-on-kev:true}")
    private boolean defaultFailOnKev;
    @Value("${oswl.gate.fail-on-epss:0.5}")
    private double defaultFailOnEpss;
    @Value("${oswl.gate.fail-on-license-violation:true}")
    private boolean defaultFailOnLicenseViolation;
    @Value("${oswl.gate.only-new:true}")
    private boolean defaultOnlyNew;
    @Value("${oswl.gate.only-reachable:false}")
    private boolean defaultOnlyReachable;
    @Value("${oswl.gate.fail-on-secrets:false}")
    private boolean defaultFailOnSecrets;

    /**
     * Per-request overrides; null fields fall back to the org/team/project policy hierarchy
     * ({@link PolicyService}), and fields the hierarchy also leaves null fall through to the
     * configured {@code oswl.gate.*} instance defaults.
     */
    public record GateOptions(
            Long scanId,
            String failOnSeverity,
            Boolean failOnKev,
            Double failOnEpss,
            Boolean failOnLicenseViolation,
            Boolean onlyNew,
            Boolean onlyReachable,
            Boolean failOnSecrets) {
        public static GateOptions defaults() {
            return new GateOptions(null, null, null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public GateResultDto evaluate(Long projectId, GateOptions options) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Tier 2 of the resolution order — org/team/project policy hierarchy.
        // Fields the hierarchy leaves null (no policy row, or policy exists but doesn't set
        // that field) fall through to the oswl.gate.* instance defaults below.
        GateOptions policyOptions = policyService.resolveGateOptions(projectId);

        RiskLevel failOnSeverity = parseSeverity(firstNonNull(
                options.failOnSeverity(), policyOptions.failOnSeverity(), defaultFailOnSeverity));
        boolean failOnKev = firstNonNull(options.failOnKev(), policyOptions.failOnKev(), defaultFailOnKev);
        double failOnEpss = firstNonNull(options.failOnEpss(), policyOptions.failOnEpss(), defaultFailOnEpss);
        boolean failOnLicense = firstNonNull(options.failOnLicenseViolation(),
                policyOptions.failOnLicenseViolation(), defaultFailOnLicenseViolation);
        boolean onlyNew = firstNonNull(options.onlyNew(), policyOptions.onlyNew(), defaultOnlyNew);
        boolean onlyReachable = firstNonNull(options.onlyReachable(), policyOptions.onlyReachable(), defaultOnlyReachable);
        boolean failOnSecrets = firstNonNull(options.failOnSecrets(), policyOptions.failOnSecrets(), defaultFailOnSecrets);

        List<PolicyException> activeExceptions = policyService.findActiveExceptions(projectId);

        Thresholds thresholds = new Thresholds(
                failOnSeverity.name(), failOnKev, failOnEpss >= 0 ? failOnEpss : null, failOnLicense, failOnSecrets);

        // Resolve the scan to gate: explicit id, else latest completed
        ScanResult scan;
        if (options.scanId() != null) {
            scan = scanResultRepository.findByIdAndProjectId(options.scanId(), projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Scan not found: " + options.scanId()));
        } else {
            scan = scanResultRepository.findRecentCompleted(projectId, 1).stream()
                    .findFirst()
                    .orElseThrow(() -> new ConflictException(
                            "Project has no completed scan to gate on"));
        }

        // Baseline = most recent completed scan before this one (for new-vuln detection)
        ScanResult baseline = scanResultRepository.findRecentCompleted(projectId, 10).stream()
                .filter(s -> !s.getId().equals(scan.getId()))
                .findFirst()
                .orElse(null);
        // Load the baseline components once — both key sets are derived from the same rows
        // (the fetch-joined query is heavy, so calling it twice doubled the baseline cost).
        List<ScanComponent> baselineComponents = baseline != null
                ? scanComponentRepository.findByScanResultId(baseline.getId())
                : List.of();
        Set<String> baselineVulnKeys = collectVulnKeys(baselineComponents);
        Set<String> baselineLicenseKeys = collectLicenseViolationKeys(baselineComponents);
        Set<String> baselineMaliciousKeys = collectMaliciousKeys(baselineComponents);

        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());

        List<Violation> violations = new ArrayList<>();
        long unanalysed = components.stream().filter(c -> !c.getLibrary().isVulnerabilitiesAnalyzed()).count();
        boolean scanCompleted = scan.getStatus() == ScanStatus.COMPLETED;
        boolean detailsAvailable = !scan.isArchived();
        Coverage coverage = new Coverage(components.size(), unanalysed, scanCompleted, detailsAvailable,
                scanCompleted && detailsAvailable && unanalysed == 0);
        if (!coverage.complete()) {
            violations.add(new Violation("COVERAGE", "INCOMPLETE_ANALYSIS", "scan", "UNKNOWN", null, false,
                    "Cannot establish complete stored lookup coverage: scanCompleted=" + scanCompleted
                            + ", detailsAvailable=" + detailsAvailable + ", unanalysedComponents=" + unanalysed,
                    false));
        }
        int evaluated = 0;
        int newVulnCount = 0;

        for (ScanComponent sc : components) {
            // Accepted exceptions never fail the gate
            if (sc.isDeferred() || sc.isIgnored()) continue;
            Library lib = sc.getLibrary();
            String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");

            // Confirmed-malicious packages (OSV MAL- advisories) block unconditionally —
            // severity/KEV/EPSS thresholds and onlyNew/onlyReachable do not apply. The only release
            // valve is an approved policy exception (waiver).
            if (lib.isMalicious()
                    && !isWaived(activeExceptions, PolicyExceptionTargetType.MALICIOUS, null, coord)) {
                boolean isNew = !baselineMaliciousKeys.contains(coord + "|MALICIOUS");
                evaluated++;
                if (isNew) newVulnCount++;
                violations.add(new Violation(
                        "MALICIOUS", lib.getName(), coord, "CRITICAL", null, false,
                        "package confirmed malicious (OSV MAL- advisory)", isNew));
            }

            // Opt-in reference filtering excludes UNKNOWN CVE findings; it is not a safety proof.
            // Both bytecode references and supported source imports qualify. License/malware
            // findings are independent of this filter. The default evaluates UNKNOWN as well.
            boolean reachabilityGatePasses = !onlyReachable || sc.getReachability() == Reachability.REACHABLE;

            if (reachabilityGatePasses) {
                for (Cve cve : lib.getCves()) {
                    if (cve.getSeverity() == null) continue;
                    String vulnId = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
                    if (vulnId == null) continue;
                    boolean isNew = !baselineVulnKeys.contains(coord + "|" + vulnId);
                    if (onlyNew && !isNew) continue;
                    if (isWaived(activeExceptions, PolicyExceptionTargetType.CVE, vulnId, coord)) continue;
                    evaluated++;
                    if (isNew) newVulnCount++;

                    List<String> reasons = new ArrayList<>();
                    boolean sevFail = cve.getSeverity().ordinal() <= failOnSeverity.ordinal()
                            && cve.getSeverity() != RiskLevel.NONE;
                    if (sevFail) reasons.add("severity " + cve.getSeverity() + " ≥ " + failOnSeverity.name());
                    if (failOnKev && Boolean.TRUE.equals(cve.getKevListed())) reasons.add("CISA KEV listed");
                    if (failOnEpss >= 0 && cve.getEpssScore() != null && cve.getEpssScore() >= failOnEpss) {
                        reasons.add(String.format("EPSS %.3f ≥ %.3f", cve.getEpssScore(), failOnEpss));
                    }
                    if (!reasons.isEmpty()) {
                        violations.add(new Violation(
                                "CVE", vulnId, coord, cve.getSeverity().name(),
                                cve.getEpssScore(), Boolean.TRUE.equals(cve.getKevListed()),
                                String.join("; ", reasons), isNew));
                    }
                }
            }

            // License policy violation (RESTRICTED)
            if (failOnLicense && lib.getLicenseStatus() == LicenseStatus.RESTRICTED) {
                String licKey = coord + "|LICENSE";
                boolean isNew = !baselineLicenseKeys.contains(licKey);
                boolean waived = isWaived(activeExceptions, PolicyExceptionTargetType.LICENSE, null, coord);
                if ((!onlyNew || isNew) && !waived) {
                    evaluated++;
                    if (isNew) newVulnCount++;
                    violations.add(new Violation(
                            "LICENSE",
                            lib.getLicenseName() != null ? lib.getLicenseName() : "Unknown",
                            coord, "RESTRICTED", null, false,
                            "license policy violation (RESTRICTED)", isNew));
                }
            }
        }

        // Secret findings — CRITICAL/HIGH severity findings only; MEDIUM/LOW never block.
        if (failOnSecrets) {
            Set<String> baselineSecretKeys = baseline != null
                    ? collectSecretKeys(baseline.getId(), projectId)
                    : Set.of();
            for (ScanFinding finding : scanFindingRepository.findByScanResultIdAndProjectId(scan.getId(), projectId)) {
                if (finding.getType() != ScanFindingType.SECRET) continue;
                if (finding.getSeverity() != RiskLevel.CRITICAL && finding.getSeverity() != RiskLevel.HIGH) continue;
                String key = secretFindingKey(finding);
                boolean isNew = !baselineSecretKeys.contains(key);
                if (onlyNew && !isNew) continue;
                evaluated++;
                if (isNew) newVulnCount++;
                violations.add(new Violation(
                        "SECRET", finding.getRuleId(), finding.getFilePath(),
                        finding.getSeverity().name(), null, false,
                        finding.getDescription() + (finding.getLineNumber() != null
                                ? " (line " + finding.getLineNumber() + ")" : ""),
                        isNew));
            }
        }

        // Most severe first
        violations.sort(Comparator.comparingInt(GatePolicyService::violationRank));

        boolean passed = violations.isEmpty();
        String baselineVersion = baseline != null
                ? (baseline.getVersion() != null ? baseline.getVersion() : "prev-scan")
                : "(none)";
        String scanVersion = scan.getVersion() != null ? scan.getVersion() : "-";
        String summary = buildSummary(passed, violations, onlyNew);
        String comment = buildComment(project.getName(), scanVersion, baselineVersion,
                passed, thresholds, onlyNew, onlyReachable, violations, summary);

        log.info("[Gate] projectId={} scanId={} passed={} violations={} evaluated={} newVulns={} onlyReachable={}",
                projectId, scan.getId(), passed, violations.size(), evaluated, newVulnCount, onlyReachable);
        if (oswlMetrics != null) {
            oswlMetrics.recordGateEvaluation(passed);
        }

        return new GateResultDto(
                passed, passed ? 0 : 1,
                project.getName(), scan.getId(), scanVersion, baselineVersion,
                onlyNew, onlyReachable, thresholds, evaluated, newVulnCount, violations,
                summary, comment, null, coverage);
    }

    // ── Baseline key collection ──────────────────────────────────────────

    private Set<String> collectVulnKeys(List<ScanComponent> components) {
        Set<String> keys = new HashSet<>();
        for (ScanComponent sc : components) {
            Library lib = sc.getLibrary();
            String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");
            for (Cve cve : lib.getCves()) {
                String vulnId = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
                if (vulnId != null) keys.add(coord + "|" + vulnId);
            }
        }
        return keys;
    }

    private Set<String> collectLicenseViolationKeys(List<ScanComponent> components) {
        Set<String> keys = new HashSet<>();
        for (ScanComponent sc : components) {
            Library lib = sc.getLibrary();
            if (lib.getLicenseStatus() == LicenseStatus.RESTRICTED) {
                String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");
                keys.add(coord + "|LICENSE");
            }
        }
        return keys;
    }

    private Set<String> collectMaliciousKeys(List<ScanComponent> components) {
        Set<String> keys = new HashSet<>();
        for (ScanComponent sc : components) {
            Library lib = sc.getLibrary();
            if (lib.isMalicious()) {
                String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");
                keys.add(coord + "|MALICIOUS");
            }
        }
        return keys;
    }

    private Set<String> collectSecretKeys(Long scanResultId, Long projectId) {
        Set<String> keys = new HashSet<>();
        for (ScanFinding f : scanFindingRepository.findByScanResultIdAndProjectId(scanResultId, projectId)) {
            if (f.getType() == ScanFindingType.SECRET) keys.add(secretFindingKey(f));
        }
        return keys;
    }

    /** Same secret at the same location across scans — file path + rule id + value fingerprint. */
    private static String secretFindingKey(ScanFinding f) {
        return f.getFilePath() + "|" + f.getRuleId() + "|" + f.getFingerprint();
    }

    // ── Formatting ───────────────────────────────────────────────────────

    private static int violationRank(Violation v) {
        if ("COVERAGE".equals(v.type())) return -2;
        if ("MALICIOUS".equals(v.type())) return -1;
        if ("SECRET".equals(v.type())) return 0;
        if ("LICENSE".equals(v.type())) return 5;
        return switch (v.severity()) {
            case "CRITICAL" -> 0;
            case "HIGH" -> 1;
            case "MEDIUM" -> 2;
            case "LOW" -> 3;
            default -> 4;
        };
    }

    private String buildSummary(boolean passed, List<Violation> violations, boolean onlyNew) {
        if (passed) {
            return onlyNew
                    ? "Security gate passed — no new blocking vulnerabilities or license violations."
                    : "Security gate passed — no blocking vulnerabilities or license violations.";
        }
        long mal = violations.stream().filter(v -> "MALICIOUS".equals(v.type())).count();
        long cve = violations.stream().filter(v -> "CVE".equals(v.type())).count();
        long lic = violations.stream().filter(v -> "LICENSE".equals(v.type())).count();
        long sec = violations.stream().filter(v -> "SECRET".equals(v.type())).count();
        String malPart = mal > 0 ? mal + " confirmed-malicious package(s), " : "";
        String secPart = sec > 0 ? sec + " secret finding(s), " : "";
        String coveragePart = violations.stream().anyMatch(v -> "COVERAGE".equals(v.type()))
                ? "incomplete analysis coverage, " : "";
        return "Security gate failed — " + coveragePart + malPart + secPart + cve + " vulnerability finding(s) and "
                + lic + " license violation(s) breach the policy.";
    }

    private String buildComment(String projectName, String scanVersion, String baselineVersion,
                                boolean passed, Thresholds t, boolean onlyNew, boolean onlyReachable,
                                List<Violation> violations, String summary) {
        StringBuilder md = new StringBuilder();
        md.append(passed ? "## ✅ OsWL Security Gate — Passed\n\n" : "## ❌ OsWL Security Gate — Failed\n\n");
        md.append(summary).append("\n\n");
        md.append("**Project:** ").append(projectName)
          .append(" · **Version:** ").append(scanVersion)
          .append(" · **Baseline:** ").append(baselineVersion)
          .append(" · **Scope:** ").append(onlyNew ? "new findings only" : "all findings").append("\n\n");
        md.append("**Policy:** fail on severity ≥ ").append(t.failOnSeverity());
        if (t.failOnKev()) md.append(", KEV-listed");
        if (t.failOnEpss() != null) md.append(String.format(", EPSS ≥ %.2f", t.failOnEpss()));
        if (t.failOnLicenseViolation()) md.append(", license violations");
        if (t.failOnSecrets()) md.append(", secrets (CRITICAL/HIGH)");
        if (onlyReachable) md.append(", referenced libraries only (bytecode/source imports; UNKNOWN excluded, execution not proven)");
        md.append("\n\n");

        if (!violations.isEmpty()) {
            md.append("| Type | ID | Component | Severity | EPSS | KEV | New | Reason |\n");
            md.append("|------|----|-----------|----------|------|-----|-----|--------|\n");
            for (Violation v : violations) {
                md.append("| ").append(v.type())
                  .append(" | ").append(v.id())
                  .append(" | `").append(v.component()).append("`")
                  .append(" | ").append(v.severity())
                  .append(" | ").append(v.epss() != null ? String.format("%.3f", v.epss()) : "—")
                  .append(" | ").append(v.kev() ? "yes" : "—")
                  .append(" | ").append(v.isNew() ? "yes" : "—")
                  .append(" | ").append(v.reason())
                  .append(" |\n");
            }
            md.append("\n");
        }
        md.append("<sub>Generated by OsWL security gate.</sub>");
        return md.toString();
    }

    private RiskLevel parseSeverity(String s) {
        try {
            return RiskLevel.valueOf(s.trim().toUpperCase());
        } catch (Exception e) {
            return RiskLevel.HIGH;
        }
    }

    // ── Waivers ────────────────────────────────────────────

    /**
     * True when an active, approved exception covers this finding: an {@code ALL}-scoped
     * exception matches every finding on its target project; a {@code CVE}/{@code LICENSE}
     * exception matches only that finding type. A {@code componentCoordinate} narrows either
     * to a single component; a {@code targetId} (on non-{@code ALL} exceptions) narrows further
     * to a single CVE/GHSA id.
     */
    private boolean isWaived(List<PolicyException> exceptions, PolicyExceptionTargetType findingType,
                             String findingId, String coord) {
        for (PolicyException ex : exceptions) {
            if (ex.getTargetType() != PolicyExceptionTargetType.ALL && ex.getTargetType() != findingType) continue;
            if (ex.getComponentCoordinate() != null && !ex.getComponentCoordinate().equals(coord)) continue;
            if (ex.getTargetType() != PolicyExceptionTargetType.ALL
                    && ex.getTargetId() != null && !ex.getTargetId().equals(findingId)) continue;
            return true;
        }
        return false;
    }

    // ── Tiered default resolution ────────────────────────────────────────

    private static String firstNonNull(String request, String policy, String instanceDefault) {
        return request != null ? request : policy != null ? policy : instanceDefault;
    }

    private static boolean firstNonNull(Boolean request, Boolean policy, boolean instanceDefault) {
        return request != null ? request : policy != null ? policy : instanceDefault;
    }

    private static double firstNonNull(Double request, Double policy, double instanceDefault) {
        return request != null ? request : policy != null ? policy : instanceDefault;
    }
}
