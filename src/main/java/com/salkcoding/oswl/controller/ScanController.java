package com.salkcoding.oswl.controller;

import tools.jackson.databind.ObjectMapper;
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

    private final ScanIngestService       scanIngestService;
    private final ScanResultRepository    scanResultRepository;
    private final ScanComponentRepository scanComponentRepository;
    private final AuditLogService         auditLogService;
    private final UserDetailsService      userDetailsService;
    private final PasswordEncoder         passwordEncoder;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            HttpServletRequest request) throws Exception {

        Long projectId = (Long) request.getAttribute(ApiKeyAuthInterceptor.ATTR_PROJECT_ID);

        // Authenticate the submitter — credentials are mandatory for all CLI scans.
        // Validation happens BEFORE scan data is ingested.
        OswlUserPrincipal principal;
        try {
            principal = (OswlUserPrincipal) userDetailsService.loadUserByUsername(payload.getSubmitterEmail());
        } catch (UsernameNotFoundException e) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!passwordEncoder.matches(payload.getSubmitterPassword(), principal.getPassword())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        if (!principal.hasPermission(Permission.SCAN_SUBMIT)) {
            throw new ForbiddenException("User does not have SCAN_SUBMIT permission");
        }
        String actorEmail = payload.getSubmitterEmail();

        // Preserve raw JSON for audit — submitterPassword is @JsonProperty(WRITE_ONLY)
        // so it is never included in the serialized output stored here.
        payload.setRawJson(MAPPER.writeValueAsString(payload));

        ScanResult result = scanIngestService.ingest(projectId, payload);

        auditLogService.logAnonymous(actorEmail, "SCAN.INGEST", "PROJECT",
                projectId.toString(), payload.getVersion(), null);

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
     * Requires an active session with PROJECT_VIEW permission — unauthenticated
     * callers and users lacking project access receive 401/403.
     */
    @GetMapping("/{scanId}/status")
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasPermission(null, 'SCAN_VIEW') or hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ScanStatusResponse> scanStatus(@PathVariable Long scanId) {
        return scanResultRepository.findById(scanId)
                .map(scan -> {
                    long count = scanComponentRepository.countByScanResultId(scan.getId());
                    return ResponseEntity.ok(ScanStatusResponse.builder()
                            .scanId(scan.getId())
                            .status(scan.getStatus().name())
                            .componentCount(count)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }
}

