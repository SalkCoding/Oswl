package com.salkcoding.oswl.domain.entity.scan;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.enums.AiEnrichmentStatus;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result bundle produced by a single CLI scan.
 * Tracks CLI version, scan time, and status (PENDING → COMPLETED, etc.).
 */
@Entity
@Table(name = "scan_results", indexes = {
        // History/trend queries: ScanResultRepository.findCompletedByProjectId, findRecentCompleted
        @Index(name = "idx_scan_results_project_status_scanned", columnList = "project_id, status, scanned_at"),
        // Status polling banner + scan history page: findLatestByProjectId, findAllByProjectIdOrderByScannedAtDesc
        // (the composite above cannot serve project_id-only scans ordered by scanned_at across mixed statuses)
        @Index(name = "idx_scan_results_project_scanned", columnList = "project_id, scanned_at"),
        // AI insight backfill: findTop15ByStatusOrderByScannedAtDesc
        @Index(name = "idx_scan_results_status_scanned", columnList = "status, scanned_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Project version at the time of the scan */
    @Column(length = 50)
    private String version;

    /**
     * UI locale of the user who triggered this scan (e.g. "ko", "ja"). AI enrichment runs
     * asynchronously and loses the request locale, so it is captured here and re-bound so
     * AI insights come back in the requester's language. Null = use the configured default.
     */
    @Column(name = "ai_locale", length = 16)
    private String aiLocale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScanStatus status = ScanStatus.PENDING;

    /** Error message, etc., when AI analysis fails */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * AI enrichment progress, tracked separately from {@link #status} — a scan reaches
     * {@link ScanStatus#COMPLETED} as soon as its CVE/license data pipeline finishes; AI
     * summaries continue in the background afterward instead of blocking that completion.
     * Null on scans persisted before this column existed (or when AI was never configured) —
     * always read through {@link #getAiStatus()}, which treats null as {@code NOT_APPLICABLE}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status", length = 20)
    private AiEnrichmentStatus aiStatus;

    /** AI-generated security risk trend insight (generated during enrichment) */
    @Column(name = "security_ai_insight", columnDefinition = "TEXT")
    private String securityAiInsight;

    /** AI-generated license risk trend insight (generated during enrichment) */
    @Column(name = "license_ai_insight", columnDefinition = "TEXT")
    private String licenseAiInsight;

    /** AI-generated security posture insight — summary of the current CVE count (generated during enrichment) */
    @Column(name = "security_posture_insight", columnDefinition = "TEXT")
    private String securityPostureInsight;

    /** AI version-diff insight vs the immediately previous completed scan (generated during enrichment) */
    @Column(name = "version_diff_ai_insight", columnDefinition = "TEXT")
    private String versionDiffAiInsight;

    /** Scan id compared against when {@link #versionDiffAiInsight} was generated */
    @Column(name = "version_diff_from_scan_id")
    private Long versionDiffFromScanId;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @PrePersist
    private void initScannedAt() {
        if (this.scannedAt == null) {
            this.scannedAt = LocalDateTime.now();
        }
    }

    /** Used when injecting test data from DataInitializer, etc. */
    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }

    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanComponent> components = new ArrayList<>();

    /** Secret/IaC misconfiguration findings from the Quick Import clone. */
    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanFinding> findings = new ArrayList<>();

    /**
     * Retention policy: once true, {@link #components} has been deleted and only the
     * aggregate counts below remain — the scan is still listed (with its version/date), but its
     * per-component/CVE detail is gone. Never set directly; go through {@link #archive}.
     */
    @Column(name = "archived", nullable = false)
    @Builder.Default
    private boolean archived = false;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archived_component_count")
    private Integer archivedComponentCount;

    @Column(name = "archived_security_critical")
    private Integer archivedSecurityCritical;
    @Column(name = "archived_security_high")
    private Integer archivedSecurityHigh;
    @Column(name = "archived_security_medium")
    private Integer archivedSecurityMedium;
    @Column(name = "archived_security_low")
    private Integer archivedSecurityLow;
    @Column(name = "archived_security_unscored")
    private Integer archivedSecurityUnscored;

    @Column(name = "archived_license_critical")
    private Integer archivedLicenseCritical;
    @Column(name = "archived_license_high")
    private Integer archivedLicenseHigh;
    @Column(name = "archived_license_medium")
    private Integer archivedLicenseMedium;
    @Column(name = "archived_license_low")
    private Integer archivedLicenseLow;

    /**
     * Records the aggregate before the caller deletes {@link #components}/dependency paths —
     * this method only stamps the summary, it does not touch the component rows itself.
     */
    public void archive(int componentCount, int[] security, int[] license) {
        this.archived = true;
        this.archivedAt = LocalDateTime.now();
        this.archivedComponentCount = componentCount;
        this.archivedSecurityCritical = security[0];
        this.archivedSecurityHigh = security[1];
        this.archivedSecurityMedium = security[2];
        this.archivedSecurityLow = security[3];
        this.archivedSecurityUnscored = security[4];
        this.archivedLicenseCritical = license[0];
        this.archivedLicenseHigh = license[1];
        this.archivedLicenseMedium = license[2];
        this.archivedLicenseLow = license[3];
    }

    public void complete() {
        this.status = ScanStatus.COMPLETED;
    }

    /** Null-safe view of {@link #aiStatus} — a scan persisted before this column existed reads as {@code NOT_APPLICABLE}. */
    public AiEnrichmentStatus getAiStatus() {
        return aiStatus != null ? aiStatus : AiEnrichmentStatus.NOT_APPLICABLE;
    }

    public void markAiNotApplicable() {
        this.aiStatus = AiEnrichmentStatus.NOT_APPLICABLE;
    }

    public void markAiPending() {
        this.aiStatus = AiEnrichmentStatus.PENDING;
    }

    public void markAiRunning() {
        this.aiStatus = AiEnrichmentStatus.RUNNING;
    }

    public void markAiCompleted() {
        this.aiStatus = AiEnrichmentStatus.COMPLETED;
    }

    public void markAiFailed() {
        this.aiStatus = AiEnrichmentStatus.FAILED;
    }

    public void updateAiInsights(String securityInsight, String licenseInsight) {
        this.securityAiInsight = securityInsight;
        this.licenseAiInsight  = licenseInsight;
    }

    public void updateSecurityPostureInsight(String insight) {
        this.securityPostureInsight = insight;
    }

    public void updateVersionDiffInsight(String insight, Long fromScanId) {
        this.versionDiffAiInsight = insight;
        this.versionDiffFromScanId = fromScanId;
    }

    public void startAnalyzing() {
        this.status = ScanStatus.ANALYZING;
    }

    /** Captures the requesting user's UI locale so async AI enrichment can answer in that language. */
    public void recordAiLocale(String locale) {
        this.aiLocale = (locale != null && !locale.isBlank()) ? locale.strip() : null;
    }

    public void startScanning() {
        this.status = ScanStatus.SCANNING;
    }

    public void fail(String message) {
        this.status = ScanStatus.FAILED;
        this.errorMessage = message;
    }

    /**
     * Resets the scan for re-scanning the same version.
     * Clears the previous payload/state so new component data can be received again.
     * Callers must clear the components collection before invoking this method.
     */
    public void resetForRescan() {
        this.status = ScanStatus.PENDING;
        this.errorMessage = null;
        this.scannedAt = LocalDateTime.now();
        this.securityAiInsight = null;
        this.licenseAiInsight = null;
        this.securityPostureInsight = null;
        this.versionDiffAiInsight = null;
        this.versionDiffFromScanId = null;
        this.aiStatus = null;
        this.archived = false;
        this.archivedAt = null;
        this.archivedComponentCount = null;
        this.archivedSecurityCritical = null;
        this.archivedSecurityHigh = null;
        this.archivedSecurityMedium = null;
        this.archivedSecurityLow = null;
        this.archivedSecurityUnscored = null;
        this.archivedLicenseCritical = null;
        this.archivedLicenseHigh = null;
        this.archivedLicenseMedium = null;
        this.archivedLicenseLow = null;
    }
}


