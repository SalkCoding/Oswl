package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * API 키 발급·폐기를 담당한다.
 * 발급된 키는 CLI가 서버로 스캔 결과를 전송할 때 인증 수단으로 사용된다.
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "oswl_";
    private static final int TOKEN_BYTES = 32; // 256-bit entropy

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 새 API 키 발급 */
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

        return apiKeyRepository.save(apiKey);
    }

    /** 토큰 값으로 유효한 키 조회 (인터셉터 사용) */
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

    /** 키 폐기 */
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

    // ── 내부 ─────────────────────────────────────────────────────────────

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        // URL-safe Base64, 패딩 제거
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
