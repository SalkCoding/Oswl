package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.domain.enums.ApiKeyType;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final String PREFIX = "oswl_";
    private static final int TOKEN_BYTES = 32;
    private static final int PREFIX_COLLISION_RETRIES = 5;

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public IssuedApiKey issue(Long projectId, String label, LocalDateTime expiresAt) {
        return issue(projectId, label, expiresAt, ApiKeyType.STANDARD, null);
    }

    @Transactional
    public IssuedApiKey issueMachineToken(Long projectId, String label, LocalDateTime expiresAt, String boundUserEmail) {
        if (boundUserEmail == null || boundUserEmail.isBlank()) {
            throw new IllegalArgumentException("boundUserEmail is required for machine tokens");
        }
        User user = userRepository.findByEmail(boundUserEmail.strip())
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + boundUserEmail));
        return issue(projectId, label, expiresAt, ApiKeyType.MACHINE, user);
    }

    private IssuedApiKey issue(Long projectId, String label, LocalDateTime expiresAt,
                                 ApiKeyType keyType, User boundUser) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        String plainToken = null;
        String tokenPrefix = null;
        String tokenHash = null;

        for (int attempt = 0; attempt < PREFIX_COLLISION_RETRIES; attempt++) {
            plainToken = generateToken();
            tokenPrefix = ApiKeyTokenSupport.extractPrefix(plainToken);
            if (apiKeyRepository.findByTokenPrefix(tokenPrefix).isEmpty()) {
                tokenHash = passwordEncoder.encode(plainToken);
                break;
            }
            log.warn("[ApiKey] Token prefix collision on attempt {} — regenerating", attempt + 1);
        }
        if (tokenHash == null) {
            throw new IllegalStateException("Could not generate a unique API key prefix");
        }

        ApiKey apiKey = ApiKey.builder()
                .project(project)
                .tokenPrefix(tokenPrefix)
                .tokenHash(tokenHash)
                .label(label)
                .expiresAt(expiresAt)
                .keyType(keyType)
                .boundUser(boundUser)
                .build();

        ApiKey saved = apiKeyRepository.save(apiKey);
        log.info("[ApiKey] Issued projectId={} type={} label={} keyId={}",
                projectId, keyType, label, saved.getId());
        auditLogService.log("CLI_KEY.CREATE", "CLI_KEY",
                saved.getId().toString(),
                label != null ? label : "-",
                "projectId=" + projectId + " type=" + keyType
                        + (boundUser != null ? " boundUser=" + boundUser.getEmail() : ""));
        return new IssuedApiKey(saved, plainToken);
    }

    @Transactional
    public ApiKey validateAndRecord(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new UnauthorizedException("API key is invalid.");
        }
        String prefix = ApiKeyTokenSupport.extractPrefix(rawToken.strip());
        ApiKey key = apiKeyRepository.findByTokenPrefix(prefix)
                .orElseThrow(() -> new UnauthorizedException("API key is invalid."));

        if (!passwordEncoder.matches(rawToken.strip(), key.getTokenHash())) {
            throw new UnauthorizedException("API key is invalid.");
        }

        if (!key.isValid()) {
            throw new UnauthorizedException("API key has been revoked or expired.");
        }

        key.recordUsage();
        return key;
    }

    @Transactional
    public void revoke(Long keyId, Long projectId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey not found: " + keyId));
        if (!key.getProject().getId().equals(projectId)) {
            throw new IllegalArgumentException("Key does not belong to project " + projectId + ".");
        }
        key.revoke();
        auditLogService.log("CLI_KEY.REVOKE", "CLI_KEY",
                keyId.toString(),
                key.getLabel() != null ? key.getLabel() : "-",
                "projectId=" + projectId);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findByProject(Long projectId) {
        return apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> findAll() {
        return apiKeyRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public ApiKey toggleActive(Long keyId) {
        ApiKey key = apiKeyRepository.findWithProjectById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("ApiKey not found: " + keyId));
        if (key.isActive()) {
            key.revoke();
        } else {
            key.activate();
        }
        return key;
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
