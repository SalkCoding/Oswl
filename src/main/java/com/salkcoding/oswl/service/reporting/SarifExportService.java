package com.salkcoding.oswl.service.reporting;
import com.salkcoding.oswl.service.project.ProjectAccessService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports a project's latest completed scan as a SARIF 2.1.0 document
 * (github.com/codeql-action/upload-sarif compatible) so vulnerability findings can
 * surface in the GitHub Code Scanning "Security" tab.
 *
 * Each distinct CVE/GHSA becomes a SARIF rule (with {@code security-severity} driving
 * GitHub's severity mapping); each affected component becomes a result. Findings have no
 * real source line, so a synthetic artifact location (the ecosystem manifest hint) is used —
 * GitHub only requires a well-formed location, not an existing file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SarifExportService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final LibraryRepository libraryRepository;
    private final ProjectAccessService projectAccessService;
    private final AirgappedSnapshotService snapshotService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record SarifFile(String content, String filename) {}

    @Transactional(readOnly = true)
    public SarifFile export(Long projectId) {
        projectAccessService.assertCanViewProject(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ScanResult scan = scanResultRepository.findRecentCompleted(projectId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Project has no completed scan — run a scan before exporting SARIF"));
        List<Library> libraries = libraryRepository.findByScanResultIdWithCves(scan.getId());
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());
        Map<Long, ScanComponent> scByLibrary = new LinkedHashMap<>();
        for (ScanComponent sc : components) {
            scByLibrary.putIfAbsent(sc.getLibrary().getId(), sc);
        }

        ObjectNode sarif = objectMapper.createObjectNode();
        sarif.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        sarif.put("version", "2.1.0");
        ArrayNode runs = sarif.putArray("runs");
        ObjectNode run = runs.addObject();

        // E7: in air-gapped mode, stamp the run with the vulnerability-definition cutoff date
        // (oldest sourceAsOf across imported snapshot sources) so exported reports carry the
        // same "analyzed against definitions as of YYYY-MM-DD" provenance as the UI.
        if (airgapped) {
            LocalDate definitionAsOf = snapshotService.oldestSourceAsOf();
            if (definitionAsOf != null) {
                run.putObject("properties").put("definitionAsOf", definitionAsOf.toString());
            }
        }

        // tool.driver + rules
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", "OsWL");
        driver.put("informationUri", "https://github.com/SalkCoding/Oswl");
        driver.put("version", "1.0.4");
        ArrayNode rules = driver.putArray("rules");

        ArrayNode results = run.putArray("results");
        Map<String, Integer> ruleIndexById = new LinkedHashMap<>();

        for (Library lib : libraries) {
            ScanComponent sc = scByLibrary.get(lib.getId());
            // Accepted exceptions (deferred/ignored) are suppressed, mirroring the gate/VEX contract.
            boolean suppressed = sc != null && (sc.isDeferred() || sc.isIgnored());
            String coord = lib.getName() + "@" + (lib.getVersion() != null ? lib.getVersion() : "");
            String manifestHint = manifestHint(lib.getEcosystem());

            for (Cve cve : lib.getCves()) {
                if (cve.getSeverity() == null || cve.getSeverity() == RiskLevel.NONE) continue;
                String ruleId = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
                if (ruleId == null) continue;

                int ruleIndex = ruleIndexById.computeIfAbsent(ruleId,
                        id -> addRule(rules, id, cve));

                ObjectNode result = results.addObject();
                result.put("ruleId", ruleId);
                result.put("ruleIndex", ruleIndex);
                result.put("level", sarifLevel(cve.getSeverity()));
                String summary = cve.getSummary() != null ? cve.getSummary()
                        : (cve.getTitle() != null ? cve.getTitle() : "Known vulnerability");
                String fix = cve.getFixVersion() != null && !cve.getFixVersion().isBlank()
                        ? " Fix: upgrade to " + cve.getFixVersion() + "." : "";
                result.putObject("message").put("text",
                        lib.getName() + " " + (lib.getVersion() != null ? lib.getVersion() : "") +
                        " is affected by " + ruleId + " (" + cve.getSeverity().name() + "). " + summary + fix);

                // Synthetic location — GitHub needs a well-formed location, not a real file.
                ObjectNode physical = result.putArray("locations").addObject().putObject("physicalLocation");
                physical.putObject("artifactLocation").put("uri", manifestHint);
                physical.putObject("region").put("startLine", 1);

                // Logical location carries the exact component coordinate.
                result.putArray("logicalLocations").addObject()
                        .put("fullyQualifiedName", lib.getEcosystem() + ":" + coord);

                // Stable fingerprint so re-uploads dedupe the same finding.
                result.putObject("partialFingerprints")
                        .put("oswl/v1", lib.getEcosystem() + "|" + coord + "|" + ruleId);

                if (suppressed) {
                    result.putArray("suppressions").addObject().put("kind", "external");
                }
            }
        }

        String content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sarif);
        } catch (Exception e) {
            throw new IllegalStateException("SARIF generation failed: " + e.getMessage(), e);
        }
        String safeName = project.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
        log.info("[Sarif] Exported SARIF projectId={} scanId={} rules={} results={}",
                projectId, scan.getId(), rules.size(), results.size());
        return new SarifFile(content, safeName + "-oswl.sarif");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Adds a SARIF rule for a CVE and returns its index in the rules array. */
    private int addRule(ArrayNode rules, String ruleId, Cve cve) {
        int index = rules.size();
        ObjectNode rule = rules.addObject();
        rule.put("id", ruleId);
        rule.put("name", "VulnerableDependency");
        rule.putObject("shortDescription").put("text",
                ruleId + (cve.getTitle() != null ? " — " + cve.getTitle() : ""));
        if (cve.getSummary() != null && !cve.getSummary().isBlank()) {
            rule.putObject("fullDescription").put("text", cve.getSummary());
        }
        rule.put("helpUri", helpUri(ruleId));
        rule.putObject("defaultConfiguration").put("level", sarifLevel(cve.getSeverity()));
        ObjectNode props = rule.putObject("properties");
        if (cve.getCvssScore() != null) {
            // GitHub reads security-severity (0.0–10.0) to bucket the alert's severity.
            props.put("security-severity", String.format("%.1f", cve.getCvssScore()));
        }
        ArrayNode tags = props.putArray("tags");
        tags.add("security");
        tags.add("external/cwe/" + (cve.getCweId() != null ? cve.getCweId().toLowerCase() : "cwe-1035"));
        return index;
    }

    private static String sarifLevel(RiskLevel severity) {
        return switch (severity) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            default -> "note";
        };
    }

    private static String helpUri(String ruleId) {
        if (ruleId.startsWith("CVE-")) return "https://nvd.nist.gov/vuln/detail/" + ruleId;
        if (ruleId.startsWith("GHSA-")) return "https://github.com/advisories/" + ruleId;
        return "https://osv.dev/vulnerability/" + ruleId;
    }

    /** A plausible manifest path per ecosystem for the synthetic artifact location. */
    private static String manifestHint(String ecosystem) {
        if (ecosystem == null) return "dependencies";
        return switch (ecosystem.toUpperCase()) {
            case "MAVEN"    -> "pom.xml";
            case "NPM"      -> "package.json";
            case "PYPI"     -> "requirements.txt";
            case "GO"       -> "go.mod";
            case "CARGO"    -> "Cargo.toml";
            case "NUGET"    -> "packages.config";
            case "RUBYGEMS" -> "Gemfile.lock";
            case "COMPOSER" -> "composer.lock";
            case "CONAN"    -> "conan.lock";
            default          -> "dependencies";
        };
    }
}
