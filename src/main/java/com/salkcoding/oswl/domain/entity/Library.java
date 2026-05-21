package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A unique open-source library identified by (name, version, ecosystem).
 * Shared across all projects/scans — CVE and license data are stored here once.
 */
@Entity
@Table(name = "libraries",
        indexes = {
            @Index(name = "idx_libraries_name_version", columnList = "name, version")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_library_name_version_ecosystem",
                columnNames = {"name", "version", "ecosystem"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Library {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 100)
    private String version;

    /**
     * Package ecosystem used by deps.dev system parameter.
     * e.g. MAVEN, NPM, PYPI, GO, CARGO, NUGET, RUBYGEMS
     */
    @Column(nullable = false, length = 20)
    private String ecosystem;

    /** Primary SPDX license expression returned by deps.dev */
    @Column(name = "license_name", length = 200)
    private String licenseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", length = 20)
    @Builder.Default
    private LicenseStatus licenseStatus = LicenseStatus.UNKNOWN;

    /**
     * True when deps.dev reports this is the default (latest stable) version of the package.
     * Null means the information has not been fetched yet.
     */
    @Column(name = "is_latest_version")
    private Boolean isLatestVersion;

    /**
     * Non-null when deps.dev marks this version as deprecated.
     * Contains the deprecation reason string from deps.dev.
     */
    @Column(name = "deprecated", length = 500)
    private String deprecated;

    /**
     * The latest stable version string from deps.dev, populated only when this version
     * is not the default (i.e. isLatestVersion == false). Null if already on the latest.
     */
    @Column(name = "latest_version", length = 100)
    private String latestVersion;

    /** Timestamp of the last successful deps.dev + OSV fetch */
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /** AI-generated one-sentence compliance risk summary for this library's license (pre-generated during enrichment) */
    @Column(name = "ai_license_summary", columnDefinition = "TEXT")
    private String aiLicenseSummary;

    @OneToMany(mappedBy = "library", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Cve> cves = new ArrayList<>();

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void updateLicense(String licenseName, LicenseStatus licenseStatus) {
        this.licenseName = licenseName;
        this.licenseStatus = licenseStatus;
    }

    public void updateVersionStatus(boolean isLatestVersion, String deprecated, String latestVersion) {
        this.isLatestVersion = isLatestVersion;
        this.deprecated = (deprecated != null && !deprecated.isBlank()) ? deprecated : null;
        this.latestVersion = (latestVersion != null && !latestVersion.isBlank()) ? latestVersion : null;
    }

    public void markFetched() {
        this.fetchedAt = LocalDateTime.now();
    }

    public void updateAiLicenseSummary(String summary) {
        this.aiLicenseSummary = summary;
    }

    // ── Computed properties ──────────────────────────────────────────────

    public long countBySeverity(String severity) {
        return cves.stream()
                .filter(c -> c.getSeverity() != null && c.getSeverity().name().equalsIgnoreCase(severity))
                .count();
    }

    /**
     * Patchability computed from CVE fix versions:
     * - No CVEs → UNKNOWN
     * - Any CVE has a fixVersion → PATCHABLE
     * - All CVEs lack fixVersion → NON_PATCHABLE
     */
    public Patchability computePatchability() {
        List<Cve> activeCves = cves.stream()
                .filter(c -> c.getSeverity() != null && c.getSeverity() != RiskLevel.NONE)
                .toList();
        if (activeCves.isEmpty()) return Patchability.UNKNOWN;
        boolean anyFixed = activeCves.stream()
                .anyMatch(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank());
        return anyFixed ? Patchability.PATCHABLE : Patchability.NON_PATCHABLE;
    }

    /**
     * Highest severity CVE in this library (CRITICAL > HIGH > MEDIUM > LOW > Unscored).
     */
    public RiskLevel highestSeverity() {
        return cves.stream()
                .map(Cve::getSeverity)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(RiskLevel.NONE);
    }

    /** Best fix version — from the highest-severity CVE that has one */
    public String bestFixVersion() {
        return cves.stream()
                .filter(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank())
                .min(Comparator.comparingInt(c -> c.getSeverity() != null ? c.getSeverity().ordinal() : 999))
                .map(Cve::getFixVersion)
                .orElse(null);
    }
}
