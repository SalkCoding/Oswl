package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A bundle of results from a single CLI scan.
 * Tracks the CLI version, scan time, and status (PENDING → COMPLETED, etc.).
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

    /** Raw JSON payload sent by the CLI (for audit purposes) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb")
    private String rawPayload;

    /** Error message for AI analysis failures, etc. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** AI-generated security risk trend insight (pre-generated during enrichment) */
    @Column(name = "security_ai_insight", columnDefinition = "TEXT")
    private String securityAiInsight;

    /** AI-generated license risk trend insight (pre-generated during enrichment) */
    @Column(name = "license_ai_insight", columnDefinition = "TEXT")
    private String licenseAiInsight;

    /**
     * The user who submitted this scan (Quick Import or future user-level CLI auth).
     * Null for scans submitted via a project API key (anonymous CLI scan).
     */
    @Column(name = "submitted_by_user_id")
    private Long submittedByUserId;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @PrePersist
    private void initScannedAt() {
        if (this.scannedAt == null) {
            this.scannedAt = LocalDateTime.now();
        }
    }

    /** Used when injecting test data from DataInitializer etc. */
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
     * Resets this scan for a re-scan of the same version.
     * Clears old payload/status so new component data can be ingested fresh.
     * Callers must clear the components collection before calling this.
     */
    public void resetForRescan(String newRawPayload) {
        this.rawPayload = newRawPayload;
        this.status = ScanStatus.PENDING;
        this.errorMessage = null;
        this.scannedAt = LocalDateTime.now();
    }
}
