package com.salkcoding.oswl.service;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cyclonedx.Version;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.model.Metadata;
import org.cyclonedx.model.Property;
import org.cyclonedx.model.vulnerability.Vulnerability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a CycloneDX 1.6 SBOM from a project's latest completed scan.
 *
 * Components map from {@link ScanComponent}/{@link Library} (purl, version, license),
 * vulnerabilities from {@link Cve} rows (CVE/GHSA id, CVSS rating, fix recommendation),
 * so the export always reflects the current enrichment state — no new entities involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SbomExportService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final ProjectAccessService projectAccessService;
    private final AirgappedSnapshotService airgappedSnapshotService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    public enum Format { JSON, XML }

    public record SbomFile(String content, String filename, String mediaType) {}

    /**
     * Exports the SBOM of the project's most recent completed scan.
     *
     * @throws IllegalArgumentException when the project does not exist (→ 404)
     * @throws ConflictException        when the project has no completed scan yet (→ 409)
     */
    @Transactional(readOnly = true)
    public SbomFile export(Long projectId, Format format) {
        projectAccessService.assertCanViewProject(projectId);
        return exportUnchecked(projectId, format);
    }

    /** Same as {@link #export} but without the ACL check — system/local-dev contexts only. */
    @Transactional(readOnly = true)
    public SbomFile exportUnchecked(Long projectId, Format format) {
        return doExport(projectId, format, false);
    }

    /**
     * Exports a CycloneDX VEX document for the project's most recent completed scan:
     * the vulnerability list with an {@code analysis} block derived from the live triage
     * state (deferrals / review marks), without the full component inventory.
     * Triage changes are reflected on the next export immediately — nothing is cached.
     */
    @Transactional(readOnly = true)
    public SbomFile exportVex(Long projectId, Format format) {
        projectAccessService.assertCanViewProject(projectId);
        return doExport(projectId, format, true);
    }

    /** Same as {@link #exportVex} but without the ACL check — system/local-dev contexts only. */
    @Transactional(readOnly = true)
    public SbomFile exportVexUnchecked(Long projectId, Format format) {
        return doExport(projectId, format, true);
    }

    private SbomFile doExport(Long projectId, Format format, boolean vex) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        ScanResult scan = scanResultRepository.findRecentCompleted(projectId, 1).stream()
                .findFirst()
                .orElseThrow(() -> new ConflictException(
                        "Project has no completed scan — run a scan before exporting an SBOM"));
        List<ScanComponent> components = scanComponentRepository.findByScanResultId(scan.getId());

        Bom bom = vex
                ? buildVexBom(project, scan, components)
                : buildBom(project, scan, components);
        try {
            String content = format == Format.XML
                    ? BomGeneratorFactory.createXml(Version.VERSION_16, bom).toXmlString()
                    : BomGeneratorFactory.createJson(Version.VERSION_16, bom).toJsonString();
            String safeName = project.getName().replaceAll("[^a-zA-Z0-9._-]", "_");
            String ext = format == Format.XML ? "xml" : "json";
            String kind = vex ? "vex" : "sbom";
            log.info("[Sbom] Exported CycloneDX {} {} projectId={} scanId={} components={}",
                    ext, kind, projectId, scan.getId(), components.size());
            return new SbomFile(
                    content,
                    safeName + "-" + kind + ".cdx." + ext,
                    format == Format.XML ? "application/xml" : "application/vnd.cyclonedx+json");
        } catch (Exception e) {
            throw new IllegalStateException("SBOM generation failed: " + e.getMessage(), e);
        }
    }

    // ── Bom assembly ─────────────────────────────────────────────────────

    /**
     * E7: in air-gapped mode, stamps the oldest snapshot {@code sourceAsOf} into
     * {@code metadata.properties} as {@code oswl:definition-as-of} (ISO date), so a consumer
     * can tell which upstream-data date the results were analyzed against. Applies to both
     * SBOM and VEX exports. Adds nothing when not air-gapped or when no source has
     * provenance yet, leaving the output byte-identical to the pre-E7 behavior.
     */
    private void addDefinitionAsOf(Metadata metadata) {
        if (!airgapped) return;
        LocalDate asOf = airgappedSnapshotService.oldestSourceAsOf();
        if (asOf == null) return;
        Property property = new Property();
        property.setName("oswl:definition-as-of");
        property.setValue(asOf.toString());
        metadata.addProperty(property);
    }

    private Bom buildBom(Project project, ScanResult scan, List<ScanComponent> scanComponents) {
        Bom bom = new Bom();
        bom.setSerialNumber("urn:uuid:" + UUID.randomUUID());

        Metadata metadata = new Metadata();
        metadata.setTimestamp(new Date());
        Component root = new Component();
        root.setType(Component.Type.APPLICATION);
        root.setName(project.getName());
        if (scan.getVersion() != null) {
            root.setVersion(scan.getVersion());
        }
        root.setBomRef("root-application");
        metadata.setComponent(root);
        addDefinitionAsOf(metadata);
        bom.setMetadata(metadata);

        // One component per distinct library; one vulnerability per distinct vuln id
        // with all affected component refs merged.
        Map<Long, String> bomRefByLibraryId = new LinkedHashMap<>();
        List<Component> components = new ArrayList<>();
        for (ScanComponent sc : scanComponents) {
            Library lib = sc.getLibrary();
            if (bomRefByLibraryId.containsKey(lib.getId())) continue;
            Component component = toComponent(lib);
            bomRefByLibraryId.put(lib.getId(), component.getBomRef());
            components.add(component);
        }
        bom.setComponents(components);

        Map<String, Vulnerability> vulnById = new LinkedHashMap<>();
        for (ScanComponent sc : scanComponents) {
            Library lib = sc.getLibrary();
            String ref = bomRefByLibraryId.get(lib.getId());
            for (Cve cve : lib.getCves()) {
                addVulnerability(vulnById, cve, ref);
            }
        }
        if (!vulnById.isEmpty()) {
            bom.setVulnerabilities(new ArrayList<>(vulnById.values()));
        }
        return bom;
    }

    /**
     * VEX document: vulnerabilities + triage analysis only, no component inventory.
     * {@code affects.ref} values match the purl bom-refs of the SBOM export so the two
     * documents can be correlated.
     */
    private Bom buildVexBom(Project project, ScanResult scan, List<ScanComponent> scanComponents) {
        Bom bom = new Bom();
        bom.setSerialNumber("urn:uuid:" + UUID.randomUUID());

        Metadata metadata = new Metadata();
        metadata.setTimestamp(new Date());
        Component root = new Component();
        root.setType(Component.Type.APPLICATION);
        root.setName(project.getName());
        if (scan.getVersion() != null) {
            root.setVersion(scan.getVersion());
        }
        root.setBomRef("root-application");
        metadata.setComponent(root);
        addDefinitionAsOf(metadata);
        bom.setMetadata(metadata);

        Map<String, Vulnerability> vulnById = new LinkedHashMap<>();
        Map<String, ScanComponent> triageSourceByVulnId = new LinkedHashMap<>();
        for (ScanComponent sc : scanComponents) {
            Library lib = sc.getLibrary();
            String purl = toPurl(lib);
            String ref = purl != null ? purl : "lib-" + lib.getId();
            for (Cve cve : lib.getCves()) {
                String id = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
                if (id == null) continue;
                addVulnerability(vulnById, cve, ref);
                // One vuln can affect several components — keep the most-triaged one
                ScanComponent current = triageSourceByVulnId.get(id);
                if (current == null || triageRank(sc) > triageRank(current)) {
                    triageSourceByVulnId.put(id, sc);
                }
            }
        }
        for (Map.Entry<String, Vulnerability> entry : vulnById.entrySet()) {
            Vulnerability.Analysis analysis = toAnalysis(triageSourceByVulnId.get(entry.getKey()));
            if (analysis != null) {
                entry.getValue().setAnalysis(analysis);
            }
        }
        bom.setVulnerabilities(new ArrayList<>(vulnById.values()));
        return bom;
    }

    /** deferred > reviewed > untouched — used to pick the triage source per vulnerability */
    private static int triageRank(ScanComponent sc) {
        if (sc.isDeferred()) return 2;
        if (sc.isReviewed()) return 1;
        return 0;
    }

    /**
     * Maps the component's live triage state to a CycloneDX impact analysis:
     * <ul>
     *   <li>deferral {@code false-positive} → {@code false_positive}</li>
     *   <li>deferral {@code wont-fix} (Accepted Risk) → {@code exploitable} + response {@code will_not_fix}</li>
     *   <li>deferral {@code temporary} (Fix Planned) → {@code exploitable} + response {@code update}</li>
     *   <li>deferral {@code legal-review} / {@code other} → {@code in_triage}</li>
     *   <li>reviewed (no deferral) → {@code in_triage}</li>
     *   <li>untouched → no analysis block</li>
     * </ul>
     */
    private Vulnerability.Analysis toAnalysis(ScanComponent sc) {
        if (sc == null) return null;
        if (sc.isDeferred()) {
            Vulnerability.Analysis analysis = new Vulnerability.Analysis();
            String reason = sc.getDeferralReason() != null ? sc.getDeferralReason() : "other";
            switch (reason) {
                case "false-positive" -> analysis.setState(Vulnerability.Analysis.State.FALSE_POSITIVE);
                case "wont-fix" -> {
                    analysis.setState(Vulnerability.Analysis.State.EXPLOITABLE);
                    analysis.addResponse(Vulnerability.Analysis.Response.WILL_NOT_FIX);
                }
                case "temporary" -> {
                    analysis.setState(Vulnerability.Analysis.State.EXPLOITABLE);
                    analysis.addResponse(Vulnerability.Analysis.Response.UPDATE);
                }
                default -> analysis.setState(Vulnerability.Analysis.State.IN_TRIAGE);
            }
            StringBuilder detail = new StringBuilder("Deferred (").append(reason).append(")");
            if (sc.getDeferredByName() != null) detail.append(" by ").append(sc.getDeferredByName());
            if (sc.getDeferralExpiresAt() != null) {
                detail.append("; expires ").append(sc.getDeferralExpiresAt().toLocalDate());
            }
            if (sc.getDeferralNote() != null && !sc.getDeferralNote().isBlank()) {
                detail.append(". Note: ").append(sc.getDeferralNote());
            }
            analysis.setDetail(detail.toString());
            if (sc.getDeferredAt() != null) {
                Date deferredAt = java.sql.Timestamp.valueOf(sc.getDeferredAt());
                analysis.setFirstIssued(deferredAt);
                analysis.setLastUpdated(deferredAt);
            }
            return analysis;
        }
        if (sc.isReviewed()) {
            Vulnerability.Analysis analysis = new Vulnerability.Analysis();
            analysis.setState(Vulnerability.Analysis.State.IN_TRIAGE);
            analysis.setDetail(sc.getReviewedByName() != null
                    ? "Reviewed by " + sc.getReviewedByName()
                    : "Reviewed");
            return analysis;
        }
        return null;
    }

    private Component toComponent(Library lib) {
        Component component = new Component();
        component.setType(Component.Type.LIBRARY);
        String purl = toPurl(lib);
        if (purl != null) {
            component.setPurl(purl);
            component.setBomRef(purl);
        } else {
            component.setBomRef("lib-" + lib.getId());
        }
        // Maven names are "group:artifact" — split into group/name for the component fields
        String name = lib.getName();
        if ("MAVEN".equalsIgnoreCase(lib.getEcosystem()) && name.contains(":")) {
            int idx = name.indexOf(':');
            component.setGroup(name.substring(0, idx));
            component.setName(name.substring(idx + 1));
        } else {
            component.setName(name);
        }
        component.setVersion(lib.getVersion());

        if (lib.getLicenseName() != null && !lib.getLicenseName().isBlank()) {
            LicenseChoice licenses = new LicenseChoice();
            licenses.setExpression(new org.cyclonedx.model.license.Expression(lib.getLicenseName()));
            component.setLicenses(licenses);
        }
        return component;
    }

    private void addVulnerability(Map<String, Vulnerability> vulnById, Cve cve, String componentRef) {
        String id = cve.getCveId() != null ? cve.getCveId() : cve.getGhsaId();
        if (id == null) return;

        Vulnerability vuln = vulnById.get(id);
        if (vuln == null) {
            vuln = new Vulnerability();
            vuln.setId(id);
            vuln.setSource(vulnSource(id));
            if (cve.getSummary() != null && !cve.getSummary().isBlank()) {
                vuln.setDescription(cve.getSummary());
            } else if (cve.getTitle() != null) {
                vuln.setDescription(cve.getTitle());
            }
            if (cve.getFixVersion() != null && !cve.getFixVersion().isBlank()) {
                vuln.setRecommendation("Upgrade to version " + cve.getFixVersion() + " or later");
            }
            Integer cweNumber = parseCweNumber(cve.getCweId());
            if (cweNumber != null) {
                vuln.setCwes(List.of(cweNumber));
            }
            Vulnerability.Rating rating = toRating(cve);
            if (rating != null) {
                vuln.setRatings(new ArrayList<>(List.of(rating)));
            }
            vuln.setAffects(new ArrayList<>());
            vulnById.put(id, vuln);
        }
        boolean alreadyAffects = vuln.getAffects().stream()
                .anyMatch(a -> componentRef.equals(a.getRef()));
        if (!alreadyAffects) {
            Vulnerability.Affect affect = new Vulnerability.Affect();
            affect.setRef(componentRef);
            vuln.getAffects().add(affect);
        }
    }

    private Vulnerability.Source vulnSource(String id) {
        Vulnerability.Source source = new Vulnerability.Source();
        if (id.startsWith("CVE-")) {
            source.setName("NVD");
            source.setUrl("https://nvd.nist.gov/vuln/detail/" + id);
        } else if (id.startsWith("GHSA-")) {
            source.setName("GitHub Advisory Database");
            source.setUrl("https://github.com/advisories/" + id);
        } else {
            source.setName("OSV");
            source.setUrl("https://osv.dev/vulnerability/" + id);
        }
        return source;
    }

    private Vulnerability.Rating toRating(Cve cve) {
        if (cve.getCvssScore() == null && cve.getSeverity() == null) {
            return null;
        }
        Vulnerability.Rating rating = new Vulnerability.Rating();
        if (cve.getCvssScore() != null) {
            rating.setScore(cve.getCvssScore());
            rating.setMethod(Vulnerability.Rating.Method.CVSSV3);
        }
        if (cve.getCvss3Vector() != null && !cve.getCvss3Vector().isBlank()) {
            rating.setVector(cve.getCvss3Vector());
        }
        rating.setSeverity(toCdxSeverity(cve.getSeverity()));
        return rating;
    }

    private Vulnerability.Rating.Severity toCdxSeverity(RiskLevel level) {
        if (level == null) return Vulnerability.Rating.Severity.UNKNOWN;
        return switch (level) {
            case CRITICAL -> Vulnerability.Rating.Severity.CRITICAL;
            case HIGH     -> Vulnerability.Rating.Severity.HIGH;
            case MEDIUM   -> Vulnerability.Rating.Severity.MEDIUM;
            case LOW      -> Vulnerability.Rating.Severity.LOW;
            default       -> Vulnerability.Rating.Severity.UNKNOWN;
        };
    }

    private static Integer parseCweNumber(String cweId) {
        if (cweId == null) return null;
        String digits = cweId.replaceAll("\\D", "");
        if (digits.isEmpty()) return null;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── purl mapping ─────────────────────────────────────────────────────

    /**
     * Builds a package-url from the internal (ecosystem, name, version) coordinates.
     * Returns null when the coordinates cannot form a valid purl — the component is
     * still exported, just without a purl.
     */
    static String toPurl(Library lib) {
        String eco = lib.getEcosystem() != null ? lib.getEcosystem().toUpperCase() : "";
        String name = lib.getName();
        String version = lib.getVersion();
        try {
            String type;
            String namespace = null;
            switch (eco) {
                case "MAVEN" -> {
                    type = PackageURL.StandardTypes.MAVEN;
                    int idx = name.indexOf(':');
                    if (idx > 0) {
                        namespace = name.substring(0, idx);
                        name = name.substring(idx + 1);
                    }
                }
                case "NPM" -> {
                    type = PackageURL.StandardTypes.NPM;
                    if (name.startsWith("@") && name.contains("/")) {
                        int idx = name.indexOf('/');
                        namespace = name.substring(0, idx);
                        name = name.substring(idx + 1);
                    }
                }
                case "PYPI" -> type = PackageURL.StandardTypes.PYPI;
                case "GO" -> {
                    type = PackageURL.StandardTypes.GOLANG;
                    int idx = name.lastIndexOf('/');
                    if (idx > 0) {
                        namespace = name.substring(0, idx);
                        name = name.substring(idx + 1);
                    }
                }
                case "CARGO"    -> type = PackageURL.StandardTypes.CARGO;
                case "NUGET"    -> type = PackageURL.StandardTypes.NUGET;
                case "RUBYGEMS" -> type = PackageURL.StandardTypes.GEM;
                case "COMPOSER" -> {
                    // pkg:composer/<vendor>/<package>@<version>
                    type = "composer";
                    int idx = name.indexOf('/');
                    if (idx > 0) {
                        namespace = name.substring(0, idx);
                        name = name.substring(idx + 1);
                    }
                }
                case "CONAN"    -> type = "conan";
                default -> {
                    return null;
                }
            }
            return new PackageURL(type, namespace, name, version, null, null).canonicalize();
        } catch (MalformedPackageURLException e) {
            log.debug("[Sbom] Cannot build purl for {}:{} ({}): {}",
                    lib.getName(), lib.getVersion(), eco, e.getMessage());
            return null;
        }
    }
}
