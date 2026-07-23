package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.controller.spec.SnapshotAdminControllerSpec;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.service.AirgappedSnapshotService;
import com.salkcoding.oswl.service.AirgappedSnapshotService.SnapshotImportResult;
import com.salkcoding.oswl.service.AirgappedSnapshotService.SourceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/**
 * Air-gapped (offline snapshot) administration, SYSTEM_ADMIN only.
 *
 * GET  /api/admin/snapshot         — air-gapped flag + per-source store status
 * POST /api/admin/snapshot/import  — upload a snapshot bundle (zip of JSONL files)
 * GET  /api/admin/snapshot/export  — download a snapshot bundle built from this
 *                                    instance's fetched data (run on an online instance)
 */
@RestController
@RequestMapping("/api/admin/snapshot")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class SnapshotAdminController implements SnapshotAdminControllerSpec {

    private final AirgappedSnapshotService snapshotService;
    private final KevCatalogService kevCatalogService;
    private final AuditLogService auditLogService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    @GetMapping
    public ResponseEntity<SnapshotStatusResponse> status() {
        return ResponseEntity.ok(new SnapshotStatusResponse(airgapped, snapshotService.status()));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SnapshotImportResult> importBundle(@RequestPart("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (Exception e) {
            throw new InvalidRequestException("Failed to read uploaded file: " + e.getMessage());
        }
        SnapshotImportResult result = snapshotService.importBundle(content);
        auditLogService.log("SNAPSHOT.IMPORT", "SNAPSHOT", null,
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "snapshot.zip",
                "records=" + result.totalRecords() + " sources=" + result.sources());
        if (airgapped) {
            // Reload the in-memory KEV catalog so the new snapshot applies immediately
            kevCatalogService.refresh();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBundle() {
        byte[] bundle = snapshotService.exportBundle();
        String filename = "oswl-snapshot-" + LocalDate.now() + ".zip";
        auditLogService.log("SNAPSHOT.EXPORT", "SNAPSHOT", null, filename,
                "sizeBytes=" + bundle.length);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bundle);
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    public record SnapshotStatusResponse(boolean airgapped, List<SourceStatus> sources) {}
}
