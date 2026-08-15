package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.OnboardingProgress;
import com.salkcoding.oswl.auth.repository.OnboardingProgressRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.repository.notification.WebhookSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingProgressRepository repository;
    private final UserRepository userRepository;
    private final UserVcsConnectionRepository userVcsConnectionRepository;
    private final WebhookSettingRepository webhookSettingRepository;
    /** Present only when an OIDC provider is configured (SSO). */
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    @Transactional(readOnly = true)
    public boolean isCompleted() {
        return repository.existsById(OnboardingProgress.SINGLETON_ID);
    }

    /** Idempotent — completing (or skipping to the end of) the wizard twice is a no-op. */
    @Transactional
    public void markCompleted() {
        if (isCompleted()) return;
        try {
            repository.saveAndFlush(OnboardingProgress.create());
        } catch (DataIntegrityViolationException ignored) {
            // Another concurrent request already completed it — fine, same end state.
        }
    }

    /**
     * Per-step completion for the wizard, derived from real instance state rather than stored
     * flags — the wizard is re-runnable, so a step is "done" only while the thing it sets up
     * actually exists. The policy-defaults step has no derivable state (reviewing is the task)
     * and intentionally gets no badge.
     *
     * @param teamReady          SSO configured, or at least one teammate beyond the first admin
     * @param repoConnected      the current user has an active VCS connection
     * @param notificationsReady a webhook URL is configured and enabled
     */
    public record StepStatus(boolean teamReady, boolean repoConnected, boolean notificationsReady) {}

    @Transactional(readOnly = true)
    public StepStatus stepStatus(Long userId) {
        boolean teamReady = clientRegistrations.getIfAvailable() != null || userRepository.count() > 1;
        boolean repoConnected = userId != null
                && userVcsConnectionRepository.existsByUserIdAndActiveTrue(userId);
        boolean notificationsReady = webhookSettingRepository.findFirstByOrderByIdAsc()
                .map(w -> w.isEnabled() && w.getWebhookUrl() != null && !w.getWebhookUrl().isBlank())
                .orElse(false);
        return new StepStatus(teamReady, repoConnected, notificationsReady);
    }
}
