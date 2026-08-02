package com.salkcoding.oswl.domain.entity.ai;

import com.salkcoding.oswl.domain.enums.AiEffort;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Global AI enrichment preferences (singleton row, id=1). */
@Entity
@Table(name = "ai_preferences")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class AiPreferences {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "prompts_locale", nullable = false, length = 10)
    private String promptsLocale;

    @Column(name = "cve_limit", nullable = false)
    private int cveLimit;

    @Column(name = "license_limit", nullable = false)
    private int licenseLimit;

    /** Comma-separated RiskLevel names included in CVE AI batch (e.g. CRITICAL,HIGH) */
    @Column(name = "cve_severities", nullable = false, length = 50)
    private String cveSeverities;

    /** Nullable — falls back to prompts.properties when null */
    @Column(name = "temperature")
    private Double temperature;

    /** Nullable — falls back to prompts.properties when null */
    @Column(name = "max_tokens")
    private Integer maxTokens;

    /** Max LLM API calls per provider per calendar day (0 = unlimited) */
    @Column(name = "daily_call_cap", nullable = false)
    private int dailyCallCap;

    /** JSON map of prompt key → override template (merged over classpath properties) */
    @Column(name = "prompt_overrides", columnDefinition = "TEXT")
    private String promptOverrides;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_deployment_profile", nullable = false, length = 40)
    private DeploymentProfile defaultDeploymentProfile;

    /** How hard the model reasons per call. Null on rows written before this column existed. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reasoning_effort", length = 20)
    private AiEffort reasoningEffort;

    /**
     * Whether connecting a provider (or changing the prompt language) may regenerate insights for
     * already-completed scans in the background. Off by default — otherwise a first-time setup, or
     * every language switch, silently spends tokens re-running every recent scan. Null on rows
     * written before this column existed and is read as false.
     */
    @Column(name = "auto_backfill_insights")
    private Boolean autoBackfillInsights;

    /** Nullable override for the llama.cpp sidecar directory (falls back to oswl.ai.embedded.dir) */
    @Column(name = "embedded_dir", length = 512)
    private String embeddedDir;

    /** Nullable preferred embedded model file name (falls back to built-in preference order) */
    @Column(name = "embedded_model", length = 255)
    private String embeddedModel;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static AiPreferences defaults(String locale, int cveLimit, int licenseLimit,
                                       String cveSeverities, int dailyCallCap) {
        return AiPreferences.builder()
                .id(SINGLETON_ID)
                .promptsLocale(locale)
                .cveLimit(cveLimit)
                .licenseLimit(licenseLimit)
                .cveSeverities(cveSeverities)
                .dailyCallCap(dailyCallCap)
                .defaultDeploymentProfile(DeploymentProfile.COMMERCIAL_PRODUCT)
                .build();
    }

    /** Null-safe view of {@link #reasoningEffort} — a row persisted before the column existed reads as DEFAULT. */
    public AiEffort getReasoningEffort() {
        return reasoningEffort != null ? reasoningEffort : AiEffort.DEFAULT;
    }

    /** Null-safe view of {@link #autoBackfillInsights} — absent means "do not auto-regenerate". */
    public boolean isAutoBackfillInsights() {
        return Boolean.TRUE.equals(autoBackfillInsights);
    }

    public void update(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities,
                       Double temperature, Integer maxTokens, int dailyCallCap,
                       String promptOverrides, DeploymentProfile defaultDeploymentProfile,
                       AiEffort reasoningEffort, boolean autoBackfillInsights) {
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiEffort.DEFAULT;
        this.autoBackfillInsights = autoBackfillInsights;
        this.promptsLocale = promptsLocale;
        this.cveLimit = cveLimit;
        this.licenseLimit = licenseLimit;
        this.cveSeverities = cveSeverities;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.dailyCallCap = dailyCallCap;
        this.promptOverrides = promptOverrides;
        this.defaultDeploymentProfile = defaultDeploymentProfile != null
                ? defaultDeploymentProfile
                : DeploymentProfile.COMMERCIAL_PRODUCT;
    }

    /** Updates only the embedded sidecar overrides; blank values clear the override. */
    public void updateEmbedded(String embeddedDir, String embeddedModel) {
        this.embeddedDir = embeddedDir != null && !embeddedDir.isBlank() ? embeddedDir.strip() : null;
        this.embeddedModel = embeddedModel != null && !embeddedModel.isBlank() ? embeddedModel.strip() : null;
    }
}
