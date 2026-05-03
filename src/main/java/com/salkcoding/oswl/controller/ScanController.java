package com.salkcoding.oswl.controller;

import tools.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.controller.spec.ScanControllerSpec;
import com.salkcoding.oswl.dto.api.PingResponse;
import com.salkcoding.oswl.dto.api.ScanResponse;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ScanIngestService;
import com.salkcoding.oswl.web.interceptor.ApiKeyAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint dedicated to CLI clients.
 * All requests are authenticated first by ApiKeyAuthInterceptor.
 *
 * POST /api/scan
 *   Headers: Authorization: Bearer oswl_xxxx
 *   Body: ScanPayload (JSON)
 *
 * GET /api/scan/ping
 *   API key validity check (used by the CLI auth command)
 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController implements ScanControllerSpec {

    private final ScanIngestService scanIngestService;

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

        // Preserve raw JSON (for audit purposes) — re-serialize from DTO
        payload.setRawJson(MAPPER.writeValueAsString(payload));

        ScanResult result = scanIngestService.ingest(projectId, payload);

        return ResponseEntity.ok(ScanResponse.builder()
                .scanId(result.getId())
                .projectId(projectId)
                .version(payload.getVersion())
                .status(result.getStatus().name())
                .message("Scan received successfully")
                .build());
    }
}
