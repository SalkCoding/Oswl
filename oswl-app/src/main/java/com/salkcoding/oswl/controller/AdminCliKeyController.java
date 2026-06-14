package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.dto.api.AdminCliKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.GlobalApiKeyResponse;
import com.salkcoding.oswl.service.ApiKeyService;
import com.salkcoding.oswl.service.ApiKeyTokenSupport;
import com.salkcoding.oswl.service.IssuedApiKey;
import com.salkcoding.oswl.service.ProjectCliKeyPolicyService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Global CLI API key management for admins.
 * Provides a cross-project view of all API keys.
 */
@RestController
@RequestMapping("/api/admin/cli-keys")
@PreAuthorize("hasPermission(null, 'SETTINGS_CLI_KEY_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class AdminCliKeyController {

    private final ApiKeyService apiKeyService;
    private final AuditLogService auditLogService;
    private final ProjectCliKeyPolicyService projectCliKeyPolicyService;

    /** List all API keys across all projects */
    @GetMapping
    public ResponseEntity<List<GlobalApiKeyResponse>> listAll() {
        List<GlobalApiKeyResponse> result = apiKeyService.findAll().stream()
                .map(this::toGlobalResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /** Issue a new API key for the specified project */
    @PostMapping
    public ResponseEntity<ApiKeyIssueResponse> issue(@RequestBody AdminCliKeyIssueRequest request) {
        if (request.getProjectId() == null) {
            return ResponseEntity.badRequest().build();
        }
        String label = (request.getLabel() != null && !request.getLabel().isBlank())
                ? request.getLabel() : "CLI Key";
        projectCliKeyPolicyService.assertCanIssueNewKey(request.getProjectId());
        IssuedApiKey issued = apiKeyService.issue(request.getProjectId(), label, null);
        ApiKey key = issued.key();
        return ResponseEntity.ok(ApiKeyIssueResponse.builder()
                .id(key.getId())
                .token(issued.plainToken())
                .label(key.getLabel() != null ? key.getLabel() : "")
                .createdAt(key.getCreatedAt().toString())
                .message("API key issued. Store this token securely — it won't be shown again.")
                .build());
    }

    /** Toggle the active status of an API key */
    @PatchMapping("/{keyId}/toggle")
    public ResponseEntity<Void> toggle(@PathVariable Long keyId) {
        ApiKey key = apiKeyService.toggleActive(keyId);
        String action = key.isActive() ? "CLI_KEY.ACTIVATE" : "CLI_KEY.REVOKE";
        auditLogService.log(action, "CLI_KEY", keyId.toString(),
                (key.getLabel() != null ? key.getLabel() : "") + " / " + key.getProject().getName(), null);
        return ResponseEntity.noContent().build();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private GlobalApiKeyResponse toGlobalResponse(ApiKey key) {
        return GlobalApiKeyResponse.builder()
                .id(key.getId())
                .token(ApiKeyTokenSupport.maskForDisplay(key.getTokenPrefix()))
                .projectId(key.getProject().getId())
                .projectName(key.getProject().getName())
                .label(key.getLabel() != null ? key.getLabel() : "")
                .active(key.isActive())
                .createdAt(key.getCreatedAt().toString())
                .lastUsedAt(key.getLastUsedAt() != null ? key.getLastUsedAt().toString() : null)
                .build();
    }

}
