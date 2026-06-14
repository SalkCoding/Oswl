package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.service.ScanSubmitAuthService;
import com.salkcoding.oswl.controller.spec.ScanControllerSpec;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.dto.api.PingResponse;
import com.salkcoding.oswl.dto.api.ScanParseResponse;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.api.ScanStatusResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.service.DependencyManifestParserService;
import com.salkcoding.oswl.service.ManifestArchiveService;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ScanApiCredentialThrottleService;
import com.salkcoding.oswl.service.ScanIngestService;
import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

/**
 * REST endpoint dedicated to CLI clients.
 * All requests are authenticated by ApiKeyAuthInterceptor.
 *
 * POST /api/scan
 *   Headers: Authorization: Bearer oswl_xxxx
 *   Body: ScanPayload (JSON)
 *
 * GET /api/scan/ping
 *   API key validity check (used by the CLI auth command)
 *
 * GET /api/scan/{scanId}/status
 *   Poll endpoint for UI status badge polling.
 *   Does NOT require API key auth — uses session/project auth only.
 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController implements ScanControllerSpec {

    private final ScanIngestService scanIngestService;
    private final ScanResultRepository scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final AuditLogService auditLogService;
    private final ScanSubmitAuthService scanSubmitAuthService;
    private final ProjectAccessService projectAccessService;
    private final ScanApiCredentialThrottleService scanApiCredentialThrottleService;
    private final DependencyManifestParserService dependencyManifestParserService;
    private final ManifestArchiveService manifestArchiveService;

    /** Ping endpoint used by the CLI auth command for a connection test */
    @GetMapping("/ping")
    public ResponseEntity<PingResponse> ping(HttpServletRequest request) {
        Long projectId = (Long) request.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID);
        return ResponseEntity.ok(PingResponse.builder()
                .status("ok")
                .projectId(projectId)
                .build());
    }

    /**
     * Server-side manifest parse for the CLI.
     * Accepts a zip of project manifests (relative paths preserved); returns components
     * using the same parser as Quick Import.
     */
  /** Manifest collection rules shared with the CLI (same source as {@link ManifestCollectRules}). */
    @GetMapping("/manifest-rules")
    public ResponseEntity<ManifestCollectRules.RulesJson> manifestRules() {
        return ResponseEntity.ok(ManifestCollectRules.RulesJson.current());
    }

    @PostMapping(value = "/parse", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ScanParseResponse> parseManifests(
            @RequestPart("archive") MultipartFile archive) throws Exception {

        Path extractDir = manifestArchiveService.extractToTempDir(archive);
        try {
            String label = ManifestArchiveService.randomLabel();
            DependencyManifestParserService.ParseResult result =
                    dependencyManifestParserService.parseDependencies(extractDir, label);
            return ResponseEntity.ok(ScanParseResponse.builder()
                    .ecosystem(result.ecosystem())
                    .componentCount(result.components().size())
                    .components(result.components())
                    .build());
        } finally {
            manifestArchiveService.deleteQuietly(extractDir);
        }
    }

    @PostMapping
    public ResponseEntity<ScanResponse> receiveScan(
            @Valid @RequestBody ScanPayload payload,
            HttpServletRequest request) {

        Long projectId = (Long) request.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID);
        ApiKey apiKey = (ApiKey) request.getAttribute(ApiKeyAuthInterceptor.ATTR_API_KEY);

        String throttleEmail = apiKey != null && apiKey.isMachineToken() && apiKey.getBoundUser() != null
                ? apiKey.getBoundUser().getEmail()
                : payload.getSubmitterEmail();
        scanApiCredentialThrottleService.assertCredentialCheckAllowed(projectId, throttleEmail);

        ScanSubmitAuthService.AuthenticatedSubmitter submitter;
        try {
            submitter = scanSubmitAuthService.authenticate(
                    apiKey, projectId, payload.getSubmitterEmail(), payload.getSubmitterPassword());
        } catch (UnauthorizedException e) {
            onScanAuthFailure(projectId, throttleEmail, payload.getVersion(), "INVALID_CREDENTIALS");
            throw e;
        } catch (com.salkcoding.oswl.exception.ForbiddenException e) {
            onScanAuthFailure(projectId, throttleEmail, payload.getVersion(), "FORBIDDEN");
            throw e;
        }

        String actorEmail = submitter.actorEmail();
        scanApiCredentialThrottleService.recordCredentialSuccess(projectId, actorEmail);

        ScanResult result = scanIngestService.ingest(projectId, payload);

        auditLogService.logAnonymous(actorEmail, "SCAN.INGEST", "PROJECT",
                projectId.toString(), payload.getVersion(),
                "scanId=" + result.getId());

        return ResponseEntity.ok(ScanResponse.builder()
                .scanId(result.getId())
                .projectId(projectId)
                .version(payload.getVersion())
                .status(result.getStatus().name())
                .message("Scan received successfully")
                .build());
    }

    /**
     * Lightweight poll endpoint for the UI.
     * Returns the current scan status and component count.
     * Used for the Security Center scan progress badge (Alpine.js polling).
     */
    @GetMapping("/{scanId}/status")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'SCAN_VIEW') or hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ScanStatusResponse> scanStatus(@PathVariable Long scanId) {
        return scanResultRepository.findById(scanId)
                .map(scan -> {
                    Long projectId = scan.getProject().getId();
                    projectAccessService.assertCanViewProject(projectId);
                    long count = scanComponentRepository.countByScanResultId(scan.getId());
                    return ResponseEntity.ok(ScanStatusResponse.builder()
                            .scanId(scan.getId())
                            .status(scan.getStatus().name())
                            .componentCount(count)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    private void onScanAuthFailure(Long projectId, String actorEmail, String version, String reason) {
        scanApiCredentialThrottleService.recordCredentialFailure(projectId, actorEmail);
        auditLogService.logAnonymous(actorEmail, "SCAN.AUTH_FAILURE", "PROJECT",
                projectId != null ? projectId.toString() : null,
                version,
                "reason=" + reason);
    }
}
