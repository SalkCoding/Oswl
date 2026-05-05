package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.*;

/**
 * A CVE (or GHSA advisory) linked to a shared Library.
 * Data is fetched from deps.dev GetAdvisory + OSV querybatch + NVD (optional).
 */
@Entity
@Table(name = "library_cves",
        indexes = {
            @Index(name = "idx_library_cves_library", columnList = "library_id"),
            @Index(name = "idx_library_cves_cve_id",  columnList = "cve_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Cve {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /** GHSA advisory ID obtained from deps.dev (e.g. "GHSA-jfh8-c2jp-hdp8") */
    @Column(name = "ghsa_id", length = 40)
    private String ghsaId;

    /** CVE identifier extracted from advisory aliases (e.g. "CVE-2021-44228") */
    @Column(name = "cve_id", length = 30)
    private String cveId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private RiskLevel severity;

    /** CVSS 3.x base score (0.0 – 10.0) from deps.dev GetAdvisory or NVD */
    @Column(name = "cvss_score")
    private Double cvssScore;

    /** CVSS 3.x vector string (e.g. "CVSS:3.1/AV:N/AC:L/...") */
    @Column(name = "cvss3_vector", length = 200)
    private String cvss3Vector;

    /** Vulnerability title from deps.dev GetAdvisory */
    @Column(length = 300)
    private String title;

    /** One-line vulnerability summary from OSV */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Earliest version in which this CVE is fixed */
    @Column(name = "fix_version", length = 100)
    private String fixVersion;

    /** CWE identifier (e.g. "CWE-20"), populated only when NVD API key is configured */
    @Column(name = "cwe_id", length = 30)
    private String cweId;

    /** AI-generated one-line risk summary (nullable) */
    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void enrichFromNvd(double cvssScore, RiskLevel severity, String cweId) {
        this.cvssScore = cvssScore;
        this.severity  = severity;
        this.cweId     = cweId;
    }

    public void enrichFromAdvisory(String cveId, String title, Double cvssScore, String cvss3Vector, RiskLevel severity) {
        this.cveId       = cveId;
        this.title       = title;
        this.cvssScore   = cvssScore;
        this.cvss3Vector = cvss3Vector;
        this.severity    = severity;
    }

    public void setAiSummary(String summary) {
        this.aiSummary = summary;
    }
}
