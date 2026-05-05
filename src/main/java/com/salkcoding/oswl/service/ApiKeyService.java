package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Handles API key issuance and revocation.
 * Issued keys are used by the CLI as an authentication credential when sending scan results to the server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "oswl_";
    private static final int TOKEN_BYTES = 32; // 256-bit entropy

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Issue a new API key */
    @Transactional
    public ApiKey issue(Long projectId, String label, LocalDateTime expiresAt) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String token = generateToken();

        ApiKey apiKey = ApiKey.builder()
                .project(project)
                .token(token)
                .label(label)
                .expiresAt(expiresAt)
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);
        log.info("[ApiKey] 발급 projectId={} label={} keyId={}", projectId, label, saved.getId());
        return saved;
    }

    /** Find a valid key by token value (used by the interceptor) */
    @Transactional
    public ApiKey validateAndRecord(String rawToken) {
        ApiKey key = apiKeyRepository.findByToken(rawToken)
                .orElseThrow(() -> new com.salkcoding.oswl.exception.UnauthorizedException("Invalid API key"));

        if (!key.isValid()) {
            throw new com.salkcoding.oswl.exception.UnauthorizedException("API key is revoked or expired");
        }

        key.recordUsage();
        return key;
    }

    /** Revoke a key */
    @Transactional
    public void revoke(Long keyId, Long projectId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey not found: " + keyId));
        if (!key.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Key does not belong to project " + projectId);
        }
        key.revoke();
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByProject(Long projectId) {
        return apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        // URL-safe Base64, no padding
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
