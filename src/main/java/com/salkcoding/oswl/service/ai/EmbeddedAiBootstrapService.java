package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.service.VulnerabilityEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the "no model yet" path for Embedded AI: downloads the default Qwen3 model,
 * starts the sidecar, and — only once it's actually healthy — registers it as the active
 * LOCAL provider. Kept as a separate bean (not a method on {@link EmbeddedAiService}) purely
 * so {@code @Async} applies: methods run through the Spring proxy only when called from a
 * different bean, and this deliberately depends on {@link EmbeddedAiProviderRegistrar},
 * {@link AuditLogService} and {@link VulnerabilityEnrichmentService} — concerns
 * {@link EmbeddedAiService} itself has no business knowing about.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddedAiBootstrapService {

    private final EmbeddedAiService embeddedAiService;
    private final EmbeddedAiProviderRegistrar embeddedProviderRegistrar;
    private final AuditLogService auditLogService;
    private final VulnerabilityEnrichmentService vulnerabilityEnrichmentService;

    /**
     * Runs on a virtual thread so the HTTP request that triggered it (see
     * {@code AiSettingController#startEmbedded}) can return immediately; the caller polls
     * {@link EmbeddedAiService#status()} for download progress and the final outcome
     * ({@code running} or {@code lastError}).
     */
    @Async
    public void downloadAndStart(String requestedModel) {
        try {
            if (embeddedAiService.needsModelDownload()) {
                embeddedAiService.downloadDefaultModel();
            }
            embeddedAiService.start(requestedModel);
        } catch (Exception e) {
            // EmbeddedAiService already recorded a user-facing reason in lastError for the
            // UI to display; nothing else to do here but stop the chain.
            log.warn("[EmbeddedAI] Background download/start failed: {}", e.getMessage());
            return;
        }
        embeddedProviderRegistrar.registerAsActiveProvider(
                embeddedAiService.modelName(), embeddedAiService.baseUrl());
        auditLogService.log("AI_SETTING.EMBEDDED_START", "AI_SETTING",
                AiProvider.LOCAL.name(), AiProvider.LOCAL.name(), embeddedAiService.modelName());
        vulnerabilityEnrichmentService.backfillMissingInsightsAsync();
    }
}
