package com.salkcoding.oswl.health;

import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Readiness indicator for the active AI provider. Verifies that a provider is configured.
 * It deliberately does not call the provider on every probe to avoid rate limits and latency;
 * the embedded sidecar has a separate indicator that checks process health.
 */
@Slf4j
@Component("aiProviderHealthIndicator")
@RequiredArgsConstructor
public class AiProviderHealthIndicator implements HealthIndicator {

    private final AiSettingRepository aiSettingRepository;

    @Override
    public Health health() {
        AiSetting active = aiSettingRepository.findByActiveTrue().orElse(null);
        if (active == null) {
            return Health.unknown()
                    .withDetail("provider", "none configured")
                    .withDetail("reason", "no AI provider is currently active")
                    .build();
        }

        String providerKind = active.isEmbeddedManaged() ? "EMBEDDED" : active.getProvider().name();
        return Health.up()
                .withDetail("provider", providerKind)
                .withDetail("model", active.getModelName())
                .withDetail("baseUrl", active.getBaseUrl())
                .build();
    }
}
