package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.*;

/**
 * CVE (security vulnerability) linked to a component.
 */
@Entity
@Table(name = "cves",
        indexes = @Index(name = "idx_cves_component", columnList = "component_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Cve {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private OswlComponent component;

    /** "CVE-2024-11053" format */
    @Column(name = "cve_id", nullable = false, length = 30)
    private String cveId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RiskLevel severity;

    @Column(name = "cvss_score")
    private Double cvssScore;

    /** RCE / Injection / XSS etc. */
    @Column(length = 100)
    private String type;

    @Column(name = "discovered_on", length = 20)
    private String discoveredOn;

    /** "Direct dep." / "Transitive dep." */
    @Column(length = 100)
    private String affects;

    @Column(name = "fix_version", length = 100)
    private String fixVersion;

    /** AI-generated one-line risk summary (nullable) */
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    public void setAiSummary(String summary) {
        this.aiSummary = summary;
    }
}
