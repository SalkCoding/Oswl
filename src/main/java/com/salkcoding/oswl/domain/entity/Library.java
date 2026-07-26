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
 * Unique open-source library identified by (name, version, ecosystem).
 * CVE and license data are shared across all projects/scans, so they are stored here only once.
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
     * Package ecosystem used for the deps.dev system parameter.
     * Examples: MAVEN, NPM, PYPI, GO, CARGO, NUGET, RUBYGEMS, COMPOSER, CONAN
     */
    @Column(nullable = false, length = 20)
    private String ecosystem;

    /** Default SPDX license expression returned by deps.dev */
    @Column(name = "license_name", length = 200)
    private String licenseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", length = 20)
    @Builder.Default
    private LicenseStatus licenseStatus = LicenseStatus.UNKNOWN;

    /**
     * True when deps.dev reports this package version as the default (latest stable).
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
     * Latest stable version string fetched from deps.dev.
     * Populated only when the current version is not the latest; null if it already is.
     */
    @Column(name = "latest_version", length = 100)
    private String latestVersion;

    /** Timestamp of the last successful deps.dev + OSV fetch */
    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    /**
     * Timestamp of the last deps.dev version-metadata refresh (isLatestVersion / deprecated /
     * latestVersion / scorecard). Tracked separately from {@link #fetchedAt} (full CVE + license
     * fetch) so a cache-hit library can skip the deps.dev GetVersion refresh while this is fresh.
     */
    @Column(name = "version_meta_fetched_at")
    private LocalDateTime versionMetaFetchedAt;

    /** AI-generated one-sentence compliance risk summary for the library license (generated during enrichment) */
    @Column(name = "ai_license_summary", columnDefinition = "TEXT")
    private String aiLicenseSummary;

    /** OpenSSF Scorecard overall score (0.0–10.0) from deps.dev; null when unavailable. */
    @Column(name = "scorecard_score")
    private Double scorecardScore;

    /** Upstream project blurb (what the library is for), from the deps.dev project record. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Project homepage / documentation site; null when upstream publishes none. */
    @Column(name = "homepage", length = 500)
    private String homepage;

    /** Source repository URL (e.g. {@code https://github.com/owner/repo}); null when unknown. */
    @Column(name = "source_repo_url", length = 500)
    private String sourceRepoUrl;

    /** True when OSV flags this package version as malicious (a {@code MAL-} advisory). */
    // columnDefinition supplies a DB default so ddl-auto=update can add this NOT NULL column to an existing populated table.
    @Column(name = "malicious", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean malicious = false;

    /** True when supply-chain heuristics flag this name as a possible typosquat / dependency-confusion package. */
    @Column(name = "typosquat_risk", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean typosquatRisk = false;

    /** Human-readable reason for {@link #typosquatRisk} (matched popular name / heuristic detail); null when not flagged. */
    @Column(name = "typosquat_reason", length = 300)
    private String typosquatReason;

    @OneToMany(mappedBy = "library", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Cve> cves = new ArrayList<>();

    // ── Mutation helpers ───────────────────────────────────────────

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

    public void markVersionMetaFetched() {
        this.versionMetaFetchedAt = LocalDateTime.now();
    }

    public void updateAiLicenseSummary(String summary) {
        this.aiLicenseSummary = summary;
    }

    /**
     * Stores upstream identity metadata. Each field is only overwritten when a non-blank value
     * is supplied, so a later lookup that returns partial data cannot blank out what an earlier
     * one resolved.
     */
    public void updateProjectMetadata(String description, String homepage, String sourceRepoUrl) {
        if (description != null && !description.isBlank())     this.description = description.strip();
        if (homepage != null && !homepage.isBlank())           this.homepage = homepage.strip();
        if (sourceRepoUrl != null && !sourceRepoUrl.isBlank()) this.sourceRepoUrl = sourceRepoUrl.strip();
    }

    public void updateScorecardScore(Double scorecardScore) {
        this.scorecardScore = scorecardScore;
    }

    public void markMalicious() {
        this.malicious = true;
    }

    public void updateTyposquatRisk(boolean typosquatRisk, String typosquatReason) {
        this.typosquatRisk = typosquatRisk;
        this.typosquatReason = typosquatRisk ? typosquatReason : null;
    }

    // ── Derived properties ─────────────────────────────────────────

    public long countBySeverity(String severity) {
        return cves.stream()
                .filter(c -> c.getSeverity() != null && c.getSeverity().name().equalsIgnoreCase(severity))
                .count();
    }

    /**
     * Computes whether a fix version can be derived from the CVEs:
     * - No CVEs → UNKNOWN
     * - At least one fixVersion exists → PATCHABLE
     * - No CVEs have a fixVersion → NON_PATCHABLE
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
     * Highest-severity CVE for this library (CRITICAL > HIGH > MEDIUM > LOW > unscored).
     */
    public RiskLevel highestSeverity() {
        return cves.stream()
                .map(Cve::getSeverity)
                .filter(Objects::nonNull)
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElse(RiskLevel.NONE);
    }

    /** Best fix version — taken from the highest-severity CVE that has a fix version */
    public String bestFixVersion() {
        return cves.stream()
                .filter(c -> c.getFixVersion() != null && !c.getFixVersion().isBlank())
                .min(Comparator.comparingInt(c -> c.getSeverity() != null ? c.getSeverity().ordinal() : 999))
                .map(Cve::getFixVersion)
                .orElse(null);
    }

    /**
     * Target version for an upgrade PR: documented CVE fix first, else latest release when outdated.
     * Returns null when there is no version to bump to (UI hides the PR button; API rejects).
     */
    public String resolvePrTargetVersion() {
        String current = (version != null && !version.isBlank()) ? version : null;
        String fix = bestFixVersion();
        if (fix != null && !fix.isBlank() && !fix.equals(current)) {
            return fix;
        }
        if (latestVersion != null && !latestVersion.isBlank()
                && !Boolean.TRUE.equals(isLatestVersion)
                && !latestVersion.equals(current)) {
            return latestVersion;
        }
        return null;
    }
}
