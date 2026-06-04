package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ApiKeyControllerSpec;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.dto.api.ApiKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.ApiKeyResponse;
import com.salkcoding.oswl.service.ApiKeyService;
import com.salkcoding.oswl.service.ApiKeyTokenSupport;
import com.salkcoding.oswl.service.IssuedApiKey;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectCliKeyPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Project API key management REST endpoint.
 * Called from the UI; separate from the auth keys used by the CLI client.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/keys")
@org.springframework.security.access.prepost.PreAuthorize("hasPermission(null, 'SETTINGS_CLI_KEY_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ApiKeyController implements ApiKeyControllerSpec {

    private final ApiKeyService apiKeyService;
    private final ProjectCliKeyPolicyService projectCliKeyPolicyService;
    private final ProjectAccessService projectAccessService;

    /** List API keys for the project */
    @GetMapping
    public ResponseEntity<List<ApiKeyResponse>> list(@PathVariable Long projectId) {
        projectAccessService.assertCanViewProject(projectId);
        List<ApiKeyResponse> result = apiKeyService.findByProject(projectId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Issue a new API key */
    @PostMapping
    public ResponseEntity<ApiKeyIssueResponse> issue(
            @PathVariable Long projectId,
            @Valid @RequestBody ApiKeyIssueRequest request) {

        projectAccessService.assertCanViewProject(projectId);
        projectCliKeyPolicyService.assertCanIssueNewKey(projectId);
        IssuedApiKey issued = apiKeyService.issue(projectId, request.getLabel(), request.getExpiresAt());
        ApiKey key = issued.key();
        return ResponseEntity.ok(ApiKeyIssueResponse.builder()
                .id(key.getId())
                .token(issued.plainToken())
                .label(key.getLabel() != null ? key.getLabel() : "")
                .createdAt(key.getCreatedAt().toString())
                .message("API key issued. Store this token securely — it won't be shown again.")
                .build());
    }

    /** Revoke a key */
    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revoke(
            @PathVariable Long projectId,
            @PathVariable Long keyId) {
        projectAccessService.assertCanViewProject(projectId);
        apiKeyService.revoke(keyId, projectId);
        return ResponseEntity.noContent().build();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private ApiKeyResponse toResponse(ApiKey key) {
        return ApiKeyResponse.builder()
                .id(key.getId())
                .token(ApiKeyTokenSupport.maskForDisplay(key.getTokenPrefix()))
                .label(key.getLabel() != null ? key.getLabel() : "")
                .active(key.isActive())
                .lastUsedAt(key.getLastUsedAt() != null ? key.getLastUsedAt().toString() : null)
                .createdAt(key.getCreatedAt().toString())
                .revokedAt(key.getRevokedAt() != null ? key.getRevokedAt().toString() : null)
                .build();
    }

}
