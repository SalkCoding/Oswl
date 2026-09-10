package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.auth.dto.SecuritySettingUpdateRequest;
import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.repository.SecuritySettingRepository;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import com.salkcoding.oswl.repository.config.CacheInvalidationRepository;
import com.salkcoding.oswl.service.config.CacheInvalidationService;
import com.salkcoding.oswl.service.license.LicensePolicyService;
import com.salkcoding.oswl.service.notification.WebhookSettingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies multi-instance cache invalidation propagation: a settings mutation committed by
 * "another instance" (simulated by writing to the DB directly, bypassing the service's local
 * Caffeine cache) must be picked up by the poller, which evicts the local cache so the next
 * read returns the new value. Also pins down the documented first-poll behavior — the first
 * poll after startup records versions without evicting.
 *
 * The scheduled poll interval is set to 1h so the real scheduler never fires mid-test;
 * polling is driven manually through a fresh poller instance.
 */
@SpringBootTest(properties = "oswl.cache.invalidation.poll-interval-ms=3600000")
@DisplayName("캐시 무효화 전파(다중 인스턴스) 통합 테스트")
class CacheInvalidationPollerTest {

    @Autowired SecuritySettingService securitySettingService;
    @Autowired SecuritySettingRepository securitySettingRepository;
    @Autowired CacheInvalidationRepository cacheInvalidationRepository;
    @Autowired LicensePolicyService licensePolicyService;
    @Autowired RoleTemplateService roleTemplateService;
    @Autowired WebhookSettingService webhookSettingService;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        // Restore the singleton settings row and drop the cached copy so later test classes
        // sharing this Spring context never observe the mutated host.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                securitySettingRepository.findById(1L).ifPresent(s -> {
                    s.setMailHost(null);
                    securitySettingRepository.save(s);
                }));
        securitySettingService.evictLocalCache();
    }

    private CacheInvalidationPoller freshPoller() {
        CacheInvalidationPoller poller = new CacheInvalidationPoller(
                cacheInvalidationRepository, licensePolicyService, roleTemplateService,
                securitySettingService, webhookSettingService);
        poller.init();
        return poller;
    }

    @Test
    @DisplayName("다른 인스턴스의 버전 범프를 두 번째 폴에서 감지해 로컬 캐시를 비우고, 첫 폴에서는 비우지 않는다")
    void poll_evictsLocalCacheOnRemoteBump_butNotOnFirstPoll() {
        // Baseline mutation through the service (bumps the version row too), then warm the cache.
        SecuritySettingUpdateRequest req = new SecuritySettingUpdateRequest();
        SecuritySettingUpdateRequest.MailDto mail = new SecuritySettingUpdateRequest.MailDto();
        mail.setHost("before.example.com");
        req.setMail(mail);
        securitySettingService.update(req);
        assertThat(securitySettingService.getOrCreate().getMailHost()).isEqualTo("before.example.com");

        // Simulate another instance: mutate the settings row and bump the invalidation
        // version directly in the DB, in one transaction — this instance's Caffeine cache
        // still holds "before.example.com".
        remoteUpdateMailHost("mid.example.com");

        // First poll of a fresh poller: records versions only, no eviction — the stale
        // cached value is still served even though the DB already has the new value.
        CacheInvalidationPoller poller = freshPoller();
        poller.poll();
        assertThat(securitySettingService.getOrCreate().getMailHost()).isEqualTo("before.example.com");

        // Another remote bump; the second poll sees the version differ from the recorded
        // one, evicts the local cache, and the next read returns the other instance's value.
        remoteUpdateMailHost("after.example.com");
        poller.poll();
        assertThat(securitySettingService.getOrCreate().getMailHost()).isEqualTo("after.example.com");
    }

    /** What another instance's mutation commits: settings row + version bump in one transaction. */
    private void remoteUpdateMailHost(String host) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            SecuritySetting s = securitySettingRepository.findById(1L).orElseThrow();
            s.setMailHost(host);
            securitySettingRepository.save(s);
            cacheInvalidationRepository.incrementVersion(
                    CacheInvalidationService.SECURITY_SETTINGS, LocalDateTime.now());
        });
    }
}
