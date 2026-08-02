package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.PolicyException;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;
import com.salkcoding.oswl.domain.enums.Reachability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.gate.GateResultDto;
import com.salkcoding.oswl.dto.gate.GateResultDto.Thresholds;
import com.salkcoding.oswl.dto.gate.GateResultDto.Violation;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
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
 * {@link PolicyException} (ROADMAP A7).
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
    private final LibraryRepository libraryRepository;
    private final PolicyService policyService;

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

    /** Per-request overrides; null fields fall back to the configured defaults. */
    public record GateOptions(
            Long scanId,
            String failOnSeverity,
            Boolean failOnKev,
            Double failOnEpss,
            Boolean failOnLicenseViolation,
            Boolean onlyNew,
            Boolean onlyReachable) {
        public static GateOptions defaults() {
            return new GateOptions(null, null, null, null, null, null, null);
        }
    }

    @Transactional(readOnly = true)
    public GateResultDto evaluate(Long projectId, GateOptions options) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Tier 2 of the resolution order — org/team/project policy hierarchy (ROADMAP A7).
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

        List<PolicyException> activeExceptions = policyService.findActiveExceptions(projectId);

        Thresholds thresholds = new Thresholds(
                failOnSeverity.name(), failOnKev, failOnEpss >= 0 ? failOnEpss : null, failOnLicense);

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

        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());

        List<Violation> violations = new ArrayList<>();
        int evaluated = 0;
        int newVulnCount = 0;

        for (ScanComponent sc : components) {
            // Accepted exceptions never fail the gate
            if (sc.isDeferred() || sc.isIgnored()) continue;
            Library lib = sc.getLibrary();
            String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");

            // "Only reachable" is a noise-cut for CVE findings only (license violations don't
            // depend on whether vulnerable code is called). Components never analyzed for
            // reachability (non-Java, or Java without a configured bytecode root) stay UNKNOWN
            // and are excluded here — this option is opt-in and Java-only by design (ROADMAP A2).
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

        return new GateResultDto(
                passed, passed ? 0 : 1,
                project.getName(), scan.getId(), scanVersion, baselineVersion,
                onlyNew, onlyReachable, thresholds, evaluated, newVulnCount, violations,
                summary, comment, null);
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

    // ── Formatting ───────────────────────────────────────────────────────

    private static int violationRank(Violation v) {
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
        long cve = violations.stream().filter(v -> "CVE".equals(v.type())).count();
        long lic = violations.stream().filter(v -> "LICENSE".equals(v.type())).count();
        return "Security gate failed — " + cve + " vulnerability finding(s) and "
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
        if (onlyReachable) md.append(", reachable CVEs only (bytecode call-graph, Java)");
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

    // ── Waivers (ROADMAP A7) ────────────────────────────────────────────

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
