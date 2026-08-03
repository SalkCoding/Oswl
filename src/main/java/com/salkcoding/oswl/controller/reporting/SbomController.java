package com.salkcoding.oswl.controller.reporting;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.controller.spec.SbomControllerSpec;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.service.reporting.SbomExportService;
import com.salkcoding.oswl.service.reporting.SbomExportService.SbomFile;
import com.salkcoding.oswl.service.reporting.SarifExportService;
import com.salkcoding.oswl.service.reporting.SarifExportService.SarifFile;
import com.salkcoding.oswl.service.ingest.SbomImportService;
import com.salkcoding.oswl.service.ingest.SbomImportService.SbomImportResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * CycloneDX SBOM export/import.
 *
 * GET  /api/projects/{projectId}/sbom?format=json|xml — export the latest completed scan
 * POST /api/sbom/import — upload an SBOM as a new scan (new or existing project)
 */
@RestController
@RequiredArgsConstructor
public class SbomController implements SbomControllerSpec {

    private final SbomExportService sbomExportService;
    private final SbomImportService sbomImportService;
    private final SarifExportService sarifExportService;
    private final AuditLogService auditLogService;

    @GetMapping("/api/projects/{projectId}/sarif")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportSarif(@PathVariable Long projectId) {
        SarifFile sarif = sarifExportService.export(projectId);
        auditLogService.log("SARIF.EXPORT", "PROJECT", projectId.toString(), null, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sarif.filename() + "\"")
                .contentType(MediaType.parseMediaType("application/sarif+json"))
                .body(sarif.content().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/projects/{projectId}/sbom")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportSbom(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "json") String format) {

        SbomExportService.Format fmt = "xml".equalsIgnoreCase(format)
                ? SbomExportService.Format.XML
                : SbomExportService.Format.JSON;
        SbomFile sbom = sbomExportService.export(projectId, fmt);

        auditLogService.log("SBOM.EXPORT", "PROJECT", projectId.toString(), null,
                "format=" + fmt.name().toLowerCase());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sbom.filename() + "\"")
                .contentType(MediaType.parseMediaType(sbom.mediaType()))
                .body(sbom.content().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/projects/{projectId}/vex")
    @PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<byte[]> exportVex(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "json") String format) {

        SbomExportService.Format fmt = "xml".equalsIgnoreCase(format)
                ? SbomExportService.Format.XML
                : SbomExportService.Format.JSON;
        SbomFile vex = sbomExportService.exportVex(projectId, fmt);

        auditLogService.log("VEX.EXPORT", "PROJECT", projectId.toString(), null,
                "format=" + fmt.name().toLowerCase());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + vex.filename() + "\"")
                .contentType(MediaType.parseMediaType(vex.mediaType()))
                .body(vex.content().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/api/sbom/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'PROJECT_CREATE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<SbomImportResult> importSbom(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectName,
            @RequestParam(required = false) String version) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw new InvalidRequestException("Failed to read uploaded file: " + e.getMessage());
        }
        SbomImportResult result = sbomImportService.importSbom(projectId, projectName, version, content);
        return ResponseEntity.ok(result);
    }
}
