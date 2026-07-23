package com.salkcoding.oswl.service;

import com.github.packageurl.PackageURL;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.dto.scan.ScanPayload.ComponentPayload;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cyclonedx.exception.ParseException;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.parsers.JsonParser;
import org.cyclonedx.parsers.XmlParser;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Imports a CycloneDX SBOM (JSON or XML) as a scan: components are mapped from purls to the
 * internal (ecosystem, name, version) coordinates and fed through the exact same
 * {@link ScanIngestService} pipeline as Quick Import / CLI scans, so storage and async
 * enrichment behave identically.
 *
 * Raw lock files (composer.lock / conan.lock) are also accepted: they are parsed with
 * {@link DependencyManifestParserService} and imported through the same pipeline.
 *
 * Components without a purl, or with a purl type outside the supported ecosystems, are
 * skipped and counted — the import never fails because of individual components.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SbomImportService {

    /** Same ecosystems the lockfile parsers and deps.dev/OSV clients support. */
    private static final int MAX_SBOM_BYTES = 20 * 1024 * 1024; // 20 MB guard

    private final ProjectRepository projectRepository;
    private final ProjectService projectService;
    private final ProjectAccessService projectAccessService;
    private final ScanIngestService scanIngestService;
    private final AuditLogService auditLogService;
    private final DependencyManifestParserService dependencyManifestParserService;

    public record SbomImportResult(
            Long projectId,
            String projectName,
            Long scanId,
            String version,
            int importedComponents,
            int skippedComponents,
            List<String> ecosystems) {}

    /**
     * @param projectId   existing project to import into (optional — either this or projectName)
     * @param projectName name for a newly created project (used when projectId is null)
     * @param version     scan version label (optional — falls back to SBOM metadata, then "sbom-import")
     */
    public SbomImportResult importSbom(Long projectId, String projectName, String version, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidRequestException("SBOM file is empty");
        }
        if (content.length > MAX_SBOM_BYTES) {
            throw new InvalidRequestException("SBOM file exceeds the 20 MB limit");
        }

        // Raw lock-file upload (composer.lock / conan.lock) — manifest parser, same ingest pipeline.
        List<ComponentPayload> lockComponents =
                dependencyManifestParserService.parseUploadedLockFile(content, "sbom-upload");
        if (lockComponents != null) {
            if (lockComponents.isEmpty()) {
                throw new InvalidRequestException("Lock file contains no importable packages");
            }
            return importComponents(projectId, projectName, version, null, lockComponents, 0);
        }

        Bom bom = parseBom(content);

        List<ComponentPayload> components = new ArrayList<>();
        int skipped = 0;
        if (bom.getComponents() != null) {
            for (Component component : bom.getComponents()) {
                ComponentPayload payload = toComponentPayload(component);
                if (payload == null) {
                    skipped++;
                    continue;
                }
                components.add(payload);
            }
        }
        if (components.isEmpty()) {
            throw new InvalidRequestException(
                    "SBOM contains no importable components (purl with a supported ecosystem required)");
        }

        return importComponents(projectId, projectName, version, bom, components, skipped);
    }

    /**
     * Shared import tail: resolves the scan version and target project, ingests the
     * components, and records the audit event. {@code bom} is null for raw lock-file imports.
     */
    private SbomImportResult importComponents(Long projectId, String projectName, String version,
                                              Bom bom, List<ComponentPayload> components, int skipped) {
        Set<String> ecosystems = new LinkedHashSet<>();
        for (ComponentPayload c : components) {
            ecosystems.add(c.getEcosystem());
        }

        String scanVersion = resolveVersion(version, bom);

        Project project;
        if (projectId != null) {
            projectAccessService.assertCanViewProject(projectId);
            project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        } else {
            String name = (projectName != null && !projectName.isBlank())
                    ? projectName.strip()
                    : deriveProjectName(bom);
            project = projectService.create(name);
        }

        ScanPayload payload = ScanPayload.create(scanVersion, components);
        ScanResult scan = scanIngestService.ingest(project.getId(), payload);

        auditLogService.log("SBOM.IMPORT", "PROJECT",
                project.getId().toString(), project.getName(),
                "scanId=" + scan.getId() + " components=" + components.size() + " skipped=" + skipped);
        log.info("[Sbom] Imported SBOM projectId={} scanId={} version={} components={} skipped={}",
                project.getId(), scan.getId(), scanVersion, components.size(), skipped);

        return new SbomImportResult(
                project.getId(), project.getName(), scan.getId(), scanVersion,
                components.size(), skipped, new ArrayList<>(ecosystems));
    }

    // ── Parsing ──────────────────────────────────────────────────────────

    private Bom parseBom(byte[] content) {
        String head = new String(content, 0, Math.min(content.length, 200), StandardCharsets.UTF_8)
                .stripLeading();
        try {
            return head.startsWith("<")
                    ? new XmlParser().parse(content)
                    : new JsonParser().parse(content);
        } catch (ParseException e) {
            throw new InvalidRequestException("Not a valid CycloneDX SBOM: " + e.getMessage());
        }
    }

    /** Maps a CycloneDX component to the internal payload via its purl. Null → skip. */
    private ComponentPayload toComponentPayload(Component component) {
        if (component.getPurl() == null || component.getPurl().isBlank()) {
            return null;
        }
        PackageURL purl;
        try {
            purl = new PackageURL(component.getPurl());
        } catch (Exception e) {
            return null;
        }
        String ecosystem = toEcosystem(purl.getType());
        if (ecosystem == null) {
            return null;
        }
        String name = toInternalName(purl, ecosystem);
        String version = purl.getVersion() != null ? purl.getVersion() : component.getVersion();
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            return null;
        }
        return ComponentPayload.create(name, version, ecosystem, "SBOM", null);
    }

    /** purl type → internal ecosystem (reverse of {@link SbomExportService#toPurl}). */
    static String toEcosystem(String purlType) {
        if (purlType == null) return null;
        return switch (purlType.toLowerCase()) {
            case PackageURL.StandardTypes.MAVEN  -> "MAVEN";
            case PackageURL.StandardTypes.NPM    -> "NPM";
            case PackageURL.StandardTypes.PYPI   -> "PYPI";
            case PackageURL.StandardTypes.GOLANG -> "GO";
            case PackageURL.StandardTypes.CARGO  -> "CARGO";
            case PackageURL.StandardTypes.NUGET  -> "NUGET";
            case PackageURL.StandardTypes.GEM    -> "RUBYGEMS";
            case "composer"                      -> "COMPOSER";
            case "conan"                         -> "CONAN";
            default -> null;
        };
    }

    /** Rebuilds the internal library name from purl namespace + name per ecosystem convention. */
    static String toInternalName(PackageURL purl, String ecosystem) {
        String namespace = purl.getNamespace();
        String name = purl.getName();
        if (namespace == null || namespace.isBlank()) {
            return name;
        }
        return switch (ecosystem) {
            case "MAVEN" -> namespace + ":" + name;   // group:artifact
            case "NPM", "GO", "COMPOSER" -> namespace + "/" + name; // @scope/pkg, module path, vendor/package
            default -> name;
        };
    }

    private String resolveVersion(String requested, Bom bom) {
        if (requested != null && !requested.isBlank()) {
            return requested.strip();
        }
        if (bom != null && bom.getMetadata() != null && bom.getMetadata().getComponent() != null
                && bom.getMetadata().getComponent().getVersion() != null
                && !bom.getMetadata().getComponent().getVersion().isBlank()) {
            return bom.getMetadata().getComponent().getVersion();
        }
        return "sbom-import";
    }

    private String deriveProjectName(Bom bom) {
        if (bom != null && bom.getMetadata() != null && bom.getMetadata().getComponent() != null
                && bom.getMetadata().getComponent().getName() != null
                && !bom.getMetadata().getComponent().getName().isBlank()) {
            return bom.getMetadata().getComponent().getName();
        }
        throw new InvalidRequestException(
                "Project name is required (the uploaded file has no project name metadata)");
    }
}
