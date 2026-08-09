package com.salkcoding.oswl.service.config;

import com.salkcoding.oswl.domain.entity.config.CacheInvalidation;
import com.salkcoding.oswl.repository.config.CacheInvalidationRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Broadcasts "this cache changed" to other instances by bumping a version row in the
 * {@code cache_invalidation} table. Caffeine caches are JVM-local, so without this a
 * settings change on one instance would only reach the others at TTL expiry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheInvalidationService {

    /** Logical cache names — one per cached service, evict-all granularity. */
    public static final String LICENSE_POLICY = "licensePolicy";
    public static final String ROLE_TEMPLATE = "roleTemplate";
    public static final String SECURITY_SETTINGS = "securitySettings";
    public static final String WEBHOOK_SETTINGS = "webhookSettings";

    private static final List<String> ALL = List.of(
            LICENSE_POLICY, ROLE_TEMPLATE, SECURITY_SETTINGS, WEBHOOK_SETTINGS);

    private final CacheInvalidationRepository cacheInvalidationRepository;
    private final PlatformTransactionManager transactionManager;

    /**
     * Pre-creates one row per known cache so {@link #bump(String)} is normally a pure
     * atomic UPDATE. Two instances booting concurrently can race on the insert; the loser
     * gets a PK violation, which is harmless — the row now exists, so it is swallowed.
     */
    @PostConstruct
    void seedRows() {
        for (String name : ALL) {
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    if (!cacheInvalidationRepository.existsById(name)) {
                        cacheInvalidationRepository.save(
                                new CacheInvalidation(name, 0L, LocalDateTime.now()));
                    }
                });
            } catch (DataIntegrityViolationException e) {
                log.debug("[CacheInvalidation] Row for '{}' already inserted by a concurrent instance", name);
            }
        }
    }

    /**
     * Records that {@code cacheName} changed. Joins the caller's transaction (REQUIRED),
     * so the bump commits — or rolls back — together with the settings mutation itself.
     */
    @Transactional
    public void bump(String cacheName) {
        int updated = cacheInvalidationRepository.incrementVersion(cacheName, LocalDateTime.now());
        if (updated == 0) {
            // Rows are seeded at startup; reaching here means a new cache type whose row
            // was never seeded — insert defensively.
            cacheInvalidationRepository.save(
                    new CacheInvalidation(cacheName, 1L, LocalDateTime.now()));
        }
    }
}
