package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    /** G5: prefetch the default model on boot so Settings doesn't surprise the user with a 1.2GB download. */
    @Value("${oswl.ai.embedded.auto-download-on-boot:true}")
    private boolean autoDownloadOnBoot;
    /** G5: never auto-download in air-gapped mode — Production-Deployment-Checklist.md tells those hosts to place the file themselves. */
    @Value("${oswl.airgapped.enabled:false}")
    private boolean airgappedEnabled;

    /**
     * G5: pulls the "click Start → surprise 1.2GB download" moment forward to app boot. Runs
     * once the application context is fully up, and only downloads — it deliberately does
     * <b>not</b> call {@link #downloadAndStart} here, since auto-starting the sidecar and
     * silently flipping the active AI provider on every fresh boot would be a much bigger,
     * unrequested behavior change than just having the file ready before the user asks for it.
     * A manual Start afterward finds the model already present (or, per the {@code downloading}
     * guard in {@link EmbeddedAiService#downloadDefaultModel()}, already in flight).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void autoDownloadOnBoot() {
        if (!autoDownloadOnBoot || airgappedEnabled) return;
        if (!embeddedAiService.needsModelDownload() || embeddedAiService.isDownloading()) return;
        log.info("[EmbeddedAI] No .gguf model present — starting background default-model download on boot");
        embeddedAiService.downloadDefaultModelAsync();
    }

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
