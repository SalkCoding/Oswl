package com.salkcoding.oswl.controller.snapshot;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.KevCatalogService;
import com.salkcoding.oswl.controller.spec.SnapshotAdminControllerSpec;
import com.salkcoding.oswl.dto.SnapshotImportFromPathRequest;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.ImportMode;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotImportResult;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SourceStatus;
import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Air-gapped (offline snapshot) administration, SYSTEM_ADMIN only.
 *
 * GET  /api/admin/snapshot              — air-gapped flag + per-source store status
 * POST /api/admin/snapshot/import       — upload a snapshot bundle (zip of JSONL files)
 * POST /api/admin/snapshot/import-from-path — import a bundle already on the server's disk
 * GET  /api/admin/snapshot/export       — download a snapshot bundle built from this
 *                                         instance's fetched data (run on an online instance)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/snapshot")
@PreAuthorize("hasPermission(null, 'SETTINGS_SNAPSHOT_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class SnapshotAdminController implements SnapshotAdminControllerSpec {

    private final AirgappedSnapshotService snapshotService;
    private final KevCatalogService kevCatalogService;
    private final AuditLogService auditLogService;

    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgapped;

    /** E3: server-path import only accepts files under this directory; blank disables the endpoint. */
    @Value("${oswl.airgapped.import-dir:}")
    private String importDir;

    /** E7: staleness badge thresholds, measured from {@link AirgappedSnapshotService#oldestSourceAsOf()}. */
    @Value("${oswl.airgapped.staleness-warn-days:7}")
    private int stalenessWarnDays;

    @Value("${oswl.airgapped.staleness-critical-days:30}")
    private int stalenessCriticalDays;

    @GetMapping
    public ResponseEntity<SnapshotStatusResponse> status() {
        return ResponseEntity.ok(new SnapshotStatusResponse(airgapped, snapshotService.status(),
                snapshotService.oldestSourceAsOf(), stalenessWarnDays, stalenessCriticalDays));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SnapshotImportResult> importBundle(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String mode) {
        ImportMode importMode = parseMode(mode);
        SnapshotImportResult result;
        // E3: the multipart upload's own stream is handed to the service directly — no
        // file.getBytes() buffering the whole compressed upload in memory up front.
        try (InputStream in = file.getInputStream()) {
            result = importMode != null
                    ? snapshotService.importBundle(in, importMode)
                    : snapshotService.importBundle(in, null);
        } catch (IOException e) {
            throw new InvalidRequestException("Failed to read uploaded file: " + e.getMessage());
        }
        auditLogService.log("SNAPSHOT.IMPORT", "SNAPSHOT", null,
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "snapshot.zip",
                "mode=" + result.mode() + " records=" + result.totalRecords() + " sources=" + result.sources());
        if (airgapped) {
            // Reload the in-memory KEV catalog so the new snapshot applies immediately
            kevCatalogService.refresh();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/import-from-path")
    public ResponseEntity<SnapshotImportResult> importFromPath(@RequestBody SnapshotImportFromPathRequest request) {
        if (importDir == null || importDir.isBlank()) {
            throw new InvalidRequestException(
                    "Server-path import is disabled — set oswl.airgapped.import-dir to enable it.");
        }
        if (request.path() == null || request.path().isBlank()) {
            throw new InvalidRequestException("path is required.");
        }
        Path resolved;
        try {
            CloneRootPathGuard guard = new CloneRootPathGuard(Path.of(importDir));
            resolved = guard.verifyContained(Path.of(importDir).resolve(request.path()));
        } catch (SecurityException | IOException e) {
            // Same message regardless of the specific reason (missing base dir vs. traversal
            // attempt vs. missing file) — do not help an attacker distinguish path-probing outcomes.
            throw new InvalidRequestException("Path is not a readable file under the configured import directory.");
        }
        if (!Files.isRegularFile(resolved)) {
            throw new InvalidRequestException("Path is not a readable file under the configured import directory.");
        }

        ImportMode importMode = parseMode(request.mode());
        SnapshotImportResult result;
        try (InputStream in = Files.newInputStream(resolved)) {
            result = snapshotService.importBundle(in, importMode);
        } catch (IOException e) {
            throw new InvalidRequestException("Failed to read bundle at " + request.path() + ": " + e.getMessage());
        }
        auditLogService.log("SNAPSHOT.IMPORT", "SNAPSHOT", null, resolved.getFileName().toString(),
                "mode=" + result.mode() + " records=" + result.totalRecords()
                        + " sources=" + result.sources() + " path=" + request.path());
        if (airgapped) {
            kevCatalogService.refresh();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping(value = "/wanted-list", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> wantedList() {
        StreamingResponseBody body = out -> {
            try {
                snapshotService.streamWantedList(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        auditLogService.log("SNAPSHOT.WANTED_LIST_EXPORT", "SNAPSHOT", null, "wanted-list.jsonl", "");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wanted-list.jsonl\"")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
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

    private static ImportMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        return switch (mode.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "merge" -> ImportMode.MERGE;
            case "replace" -> ImportMode.REPLACE;
            default -> throw new InvalidRequestException("Unknown import mode '" + mode + "' (expected replace or merge).");
        };
    }

    // ── DTO ──────────────────────────────────────────────────────────────

    /**
     * E7: {@code oldestSourceAsOf}/{@code stalenessWarnDays}/{@code stalenessCriticalDays} let the
     * admin UI render the staleness badge without hardcoding the threshold values it's configured with.
     */
    public record SnapshotStatusResponse(boolean airgapped, List<SourceStatus> sources,
                                          java.time.LocalDate oldestSourceAsOf,
                                          int stalenessWarnDays, int stalenessCriticalDays) {}
}
