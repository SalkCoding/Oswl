package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ApiKeyControllerSpec;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.dto.api.ApiKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.ApiKeyResponse;
import com.salkcoding.oswl.service.ApiKeyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 프로젝트 API 키 관리 REST 엔드포인트.
 * UI에서 호출하며, CLI 클라이언트가 사용하는 인증 키와는 별개.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/keys")
@RequiredArgsConstructor
public class ApiKeyController implements ApiKeyControllerSpec {

    private final ApiKeyService apiKeyService;

    /** 프로젝트의 키 목록 조회 */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> list(@PathVariable Long projectId) {
        List<ApiKeyResponse> result = apiKeyService.findByProject(projectId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** 새 API 키 발급 */
    @PostMapping
    public ResponseEntity<ApiKeyIssueResponse> issue(
            @PathVariable Long projectId,
            @Valid @RequestBody ApiKeyIssueRequest request) {

        ApiKey key = apiKeyService.issue(projectId, request.getLabel(), request.getExpiresAt());
        // 발급 직후에만 전체 토큰을 반환 (이후 조회 시에는 마스킹)
        return ResponseEntity.ok(ApiKeyIssueResponse.builder()
                .id(key.getId())
                .token(key.getToken())
                .label(key.getLabel() != null ? key.getLabel() : "")
                .createdAt(key.getCreatedAt().toString())
                .message("API key issued. Store this token securely — it won't be shown again.")
                .build());
    }

    /** 키 폐기 */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(
            @PathVariable Long projectId,
            @PathVariable Long keyId) {
        apiKeyService.revoke(keyId, projectId);
        return ResponseEntity.noContent().build();
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    private ApiKeyResponse toResponse(ApiKey key) {
        return ApiKeyResponse.builder()
                .id(key.getId())
                .token(maskToken(key.getToken()))
                .label(key.getLabel() != null ? key.getLabel() : "")
                .active(key.isActive())
                .lastUsedAt(key.getLastUsedAt() != null ? key.getLastUsedAt().toString() : null)
                .createdAt(key.getCreatedAt().toString())
                .build();
    }

    /** oswl_ABCDxxxxxxxx...xxxx → oswl_ABCD...xxxx (앞 9자 + 뒤 4자만 노출) */
    private String maskToken(String token) {
        if (token == null || token.length() < 14) return "***";
        return token.substring(0, 9) + "..." + token.substring(token.length() - 4);
    }
}
