package com.salkcoding.oswl.domain.entity;

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

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static AiPreferences defaults(String locale, int cveLimit, int licenseLimit, String cveSeverities) {
        return AiPreferences.builder()
                .id(SINGLETON_ID)
                .promptsLocale(locale)
                .cveLimit(cveLimit)
                .licenseLimit(licenseLimit)
                .cveSeverities(cveSeverities)
                .build();
    }

    public void update(String promptsLocale, int cveLimit, int licenseLimit, String cveSeverities) {
        this.promptsLocale = promptsLocale;
        this.cveLimit = cveLimit;
        this.licenseLimit = licenseLimit;
        this.cveSeverities = cveSeverities;
    }
}
