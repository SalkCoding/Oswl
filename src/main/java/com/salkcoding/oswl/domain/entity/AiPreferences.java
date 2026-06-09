package com.salkcoding.oswl.domain.entity;

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

    public void update(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities,
                       Double temperature, Integer maxTokens, int dailyCallCap,
                       String promptOverrides, DeploymentProfile defaultDeploymentProfile) {
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
}
