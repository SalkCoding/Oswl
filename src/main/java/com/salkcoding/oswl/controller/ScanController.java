package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.controller.spec.ScanControllerSpec;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.dto.api.PingResponse;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.api.ScanStatusResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.repository.ScanComponentRepository;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ScanApiCredentialThrottleService;
import com.salkcoding.oswl.service.ScanIngestService;
import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

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
    private final UserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final ProjectAccessService projectAccessService;
    private final ScanApiCredentialThrottleService scanApiCredentialThrottleService;

    /** Ping endpoint used by the CLI auth command for a connection test */
    @GetMapping("/ping")
    public ResponseEntity<PingResponse> ping(HttpServletRequest request) {
        Long projectId = (Long) request.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID);
        return ResponseEntity.ok(PingResponse.builder()
                .status("ok")
                .projectId(projectId)
                .build());
    }

    @PostMapping
    public ResponseEntity<ScanResponse> receiveScan(
            @Valid @RequestBody ScanPayload payload,
            HttpServletRequest request) {

        Long projectId = (Long) request.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID);
        String actorEmail = payload.getSubmitterEmail();

        scanApiCredentialThrottleService.assertCredentialCheckAllowed(projectId, actorEmail);

        OswlUserPrincipal principal;
        try {
            principal = (OswlUserPrincipal) userDetailsService.loadUserByUsername(actorEmail);
        } catch (UsernameNotFoundException e) {
            onScanAuthFailure(projectId, actorEmail, payload.getVersion(), "UNKNOWN_USER");
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!passwordEncoder.matches(payload.getSubmitterPassword(), principal.getPassword())) {
            onScanAuthFailure(projectId, actorEmail, payload.getVersion(), "INVALID_PASSWORD");
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!principal.hasPermission(Permission.SCAN_SUBMIT)) {
            onScanAuthFailure(projectId, actorEmail, payload.getVersion(), "MISSING_SCAN_SUBMIT");
            throw new ForbiddenException("User does not have SCAN_SUBMIT permission");
        }
        try {
            projectAccessService.assertCanSubmitScan(projectId, principal.getUserId());
        } catch (ForbiddenException e) {
            onScanAuthFailure(projectId, actorEmail, payload.getVersion(), "NOT_PROJECT_MEMBER");
            throw e;
        }

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
