package com.salkcoding.oswl.domain.entity;

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
@Table(name = "scan_results")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ScanStatus status = ScanStatus.PENDING;

    /** Error message, etc., when AI analysis fails */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

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

    public void complete() {
        this.status = ScanStatus.COMPLETED;
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
    }
}
