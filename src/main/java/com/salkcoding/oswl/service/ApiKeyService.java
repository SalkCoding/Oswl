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
 * API 키 발급과 취소를 처리한다.
 * 발급된 키는 CLI가 스캔 결과를 서버에 전송할 때 인증 자격증명으로 사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "oswl_";
    private static final int TOKEN_BYTES = 32; // 256비트 엔트로피

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    /** 새 API 키 발급 */
    @Transactional
    public ApiKey issue(Long projectId, String label, LocalDateTime expiresAt) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾지 못함: " + projectId));

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

    /** 토큰 값으로 유효한 키를 조회한다 (인터셉터 사용) */
    @Transactional
    public ApiKey validateAndRecord(String rawToken) {
        ApiKey key = apiKeyRepository.findByToken(rawToken)
                .orElseThrow(() -> new com.salkcoding.oswl.exception.UnauthorizedException("API 키가 유효하지 않습니다."));

        if (!key.isValid()) {
            throw new com.salkcoding.oswl.exception.UnauthorizedException("API 키가 취소되었거나 만료되었습니다.");
        }

        key.recordUsage();
        return key;
    }

    /** 키 취소 */
    @Transactional
    public void revoke(Long keyId, Long projectId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey를 찾지 못함: " + keyId));
        if (!key.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("키가 프로젝트 " + projectId + "에 속하지 않습니다.");
        }
        key.revoke();
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByProject(Long projectId) {
        return apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /** 모든 프로젝트의 키 조회 (관리자 전용) */
    @Transactional(readOnly = true)
    public List<ApiKey> findAll() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 키 활성화 상태 토글 (관리자 전용) */
    @Transactional
    public ApiKey toggleActive(Long keyId) {
        ApiKey key = apiKeyRepository.findWithProjectById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey를 찾지 못함: " + keyId));
        if (key.isActive()) {
            key.revoke();
        } else {
            key.activate();
        }
        return key;
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        // URL-safe Base64, 패딩 없음
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
