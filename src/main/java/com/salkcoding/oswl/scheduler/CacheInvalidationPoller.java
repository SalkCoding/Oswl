package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.auth.service.RoleTemplateService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.domain.entity.config.CacheInvalidation;
import com.salkcoding.oswl.repository.config.CacheInvalidationRepository;
import com.salkcoding.oswl.service.config.CacheInvalidationService;
import com.salkcoding.oswl.service.license.LicensePolicyService;
import com.salkcoding.oswl.service.notification.WebhookSettingService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Propagates cache invalidations across instances: polls the {@code cache_invalidation}
 * table and evicts the local Caffeine cache of any service whose version row changed.
 *
 * Deliberately has no {@code @SchedulerLock} — every instance must poll independently,
 * since each JVM-local cache needs its own eviction.
 *
 * Trade-offs (accepted for simplicity):
 * - The first poll after startup only records versions without evicting. This instance
 *   applied its own mutations locally already, and anything changed before boot is in its
 *   freshly warmed cache, so a first-poll eviction would just flush warm caches.
 * - A self-originated bump looks like a change on the next poll and costs one redundant
 *   local evict. An instance-id column could skip self, but isn't worth the extra wiring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationPoller {

    private final CacheInvalidationRepository cacheInvalidationRepository;
    private final LicensePolicyService licensePolicyService;
    private final RoleTemplateService roleTemplateService;
    private final SecuritySettingService securitySettingService;
    private final WebhookSettingService webhookSettingService;

    /** cache_name → local evict-all action (explicit wiring, one entry per cached service). */
    private Map<String, Runnable> evictActions;

    private final Map<String, Long> lastSeenVersions = new ConcurrentHashMap<>();
    private volatile boolean firstPollDone = false;

    @PostConstruct
    void init() {
        evictActions = Map.of(
                CacheInvalidationService.LICENSE_POLICY, licensePolicyService::evictLocalCache,
                CacheInvalidationService.ROLE_TEMPLATE, roleTemplateService::evictLocalCache,
                CacheInvalidationService.SECURITY_SETTINGS, securitySettingService::evictLocalCache,
                CacheInvalidationService.WEBHOOK_SETTINGS, webhookSettingService::evictLocalCache);
    }

    @Scheduled(fixedDelayString = "${oswl.cache.invalidation.poll-interval-ms:5000}")
    public void poll() {
        try {
            pollOnce();
        } catch (Exception e) {
            // A failed poll (DB hiccup etc.) must not kill the scheduler — retry next cycle.
            log.warn("[CacheInvalidation] Poll failed, will retry next cycle: {}", e.toString());
        }
    }

    void pollOnce() {
        List<CacheInvalidation> rows = cacheInvalidationRepository.findAll();
        if (!firstPollDone) {
            rows.forEach(row -> lastSeenVersions.put(row.getCacheName(), row.getVersion()));
            firstPollDone = true;
            return;
        }
        for (CacheInvalidation row : rows) {
            Long lastSeen = lastSeenVersions.get(row.getCacheName());
            if (lastSeen == null) {
                // Previously unknown cache name — record only, same rationale as first poll.
                lastSeenVersions.put(row.getCacheName(), row.getVersion());
                continue;
            }
            if (row.getVersion() != lastSeen) {
                Runnable evict = evictActions.get(row.getCacheName());
                if (evict != null) {
                    evict.run();
                    log.debug("[CacheInvalidation] Evicted local cache '{}' (version {} → {})",
                            row.getCacheName(), lastSeen, row.getVersion());
                }
                lastSeenVersions.put(row.getCacheName(), row.getVersion());
            }
        }
    }
}
