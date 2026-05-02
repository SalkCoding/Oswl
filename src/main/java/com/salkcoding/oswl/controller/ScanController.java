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
 * CLI 클라이언트 전용 REST 엔드포인트.
 * 모든 요청은 ApiKeyAuthInterceptor가 먼저 인증한다.
 *
 * POST /api/scan
 *   Headers: Authorization: Bearer oswl_xxxx
 *   Body: ScanPayload (JSON)
 *
 * GET /api/scan/ping
 *   API 키 유효성 확인 (CLI auth 명령에서 사용)
 */
@RestController
@RequestMapping("/api/scan")
@RequiredArgsConstructor
public class ScanController implements ScanControllerSpec {

    private final ScanIngestService scanIngestService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** CLI auth 명령이 연결 테스트에 사용하는 핑 엔드포인트 */
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

        // 원시 JSON 보존 (감사 목적) — DTO를 재직렬화
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
