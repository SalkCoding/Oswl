package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-time migration: hash legacy plaintext {@code api_keys.token} values and clear the column.
 */
@Slf4j
@Component
@Order(50)
@RequiredArgsConstructor
public class ApiKeyLegacyMigrationRunner implements ApplicationListener<ApplicationReadyEvent> {

    private final ApiKeyRepository apiKeyRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<ApiKey> pending = apiKeyRepository.findAll().stream()
                .filter(ApiKey::needsTokenMigration)
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        log.warn("[ApiKey] Migrating {} legacy plaintext API key(s) to BCrypt hashes", pending.size());
        for (ApiKey key : pending) {
            String plain = key.getLegacyToken().strip();
            key.applyTokenHash(plain, passwordEncoder.encode(plain));
            apiKeyRepository.save(key);
        }
        log.info("[ApiKey] Legacy API key migration complete");
    }
}
