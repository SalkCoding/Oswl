package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Enforces CLI API key policy for scan ingest and Quick Import key issuance.
 */
@Service
@RequiredArgsConstructor
public class ProjectCliKeyPolicyService {

    public enum ProjectKeyState {
        /** No api_keys rows for this project — may issue a new key */
        NONE,
        /** At least one active, non-expired key */
        ACTIVE_PRESENT,
        /** Key rows exist but none are valid (revoked / expired) */
        INACTIVE_ONLY
    }

    private final ApiKeyRepository apiKeyRepository;

    @Transactional(readOnly = true)
    public ProjectKeyState resolve(Long projectId) {
        List<ApiKey> keys = apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        if (keys.isEmpty()) {
            return ProjectKeyState.NONE;
        }
        boolean anyValid = keys.stream().anyMatch(ApiKey::isValid);
        return anyValid ? ProjectKeyState.ACTIVE_PRESENT : ProjectKeyState.INACTIVE_ONLY;
    }

    @Transactional(readOnly = true)
    public boolean canIssueNewKey(Long projectId) {
        return resolve(projectId) == ProjectKeyState.NONE;
    }

    @Transactional(readOnly = true)
    public void assertCanIssueNewKey(Long projectId) {
        if (canIssueNewKey(projectId)) {
            return;
        }
        String message = resolve(projectId) == ProjectKeyState.ACTIVE_PRESENT
                ? "This project already has an active CLI API key. Revoke it before issuing a new one."
                : "This project has revoked or expired CLI API keys. "
                        + "Reactivate a key in CLI Integration settings before issuing a new one.";
        throw new ConflictException(message);
    }

    @Transactional(readOnly = true)
    public void assertScanIngestAllowed(Long projectId) {
        if (resolve(projectId) == ProjectKeyState.INACTIVE_ONLY) {
            throw new ConflictException(
                    "This project has revoked or expired CLI API keys. "
                            + "Reactivate a key in CLI Integration settings before submitting scans.");
        }
    }
}
