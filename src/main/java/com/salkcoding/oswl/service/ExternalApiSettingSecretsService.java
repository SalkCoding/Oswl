package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encrypts GitHub OAuth secrets at rest and decrypts them for server-side use only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalApiSettingSecretsService implements ApplicationListener<ApplicationReadyEvent> {

    private final ExternalApiSettingRepository repository;
    private final EncryptionService encryptionService;

    public String encryptSecret(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return encryptionService.encrypt(plaintext.strip());
    }

    public String decryptSecret(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(stored);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Stored secret could not be decrypted. Re-enter it in Settings.", e);
        }
    }

    /** True when the value is our AES-GCM ciphertext (Base64 envelope). */
    public boolean isEncrypted(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            encryptionService.decrypt(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String resolveGithubClientSecret(ExternalApiSetting settings) {
        if (settings == null || settings.getGithubClientSecret() == null
                || settings.getGithubClientSecret().isBlank()) {
            return null;
        }
        return decryptSecret(settings.getGithubClientSecret());
    }

    @Transactional
    public void migratePlaintextSecretsIfNeeded() {
        repository.findFirstByOrderByIdAsc().ifPresent(this::migrateRow);
    }

    private void migrateRow(ExternalApiSetting s) {
        boolean dirty = false;
        if (s.getGithubClientSecret() != null && !s.getGithubClientSecret().isBlank()
                && !isEncrypted(s.getGithubClientSecret())) {
            String plain = s.getGithubClientSecret();
            s.updateGithubOAuth(s.getGithubClientId(), encryptSecret(plain), s.getGithubRedirectUri());
            dirty = true;
            log.info("[ExternalApi] Migrated GitHub client secret to encrypted storage");
        }
        if (dirty) {
            repository.save(s);
        }
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        migratePlaintextSecretsIfNeeded();
    }
}
