package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ScanStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
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

    @CreationTimestamp
    @Column(name = "scanned_at", updatable = false)
    private LocalDateTime scannedAt;

    /** Used when injecting test data from DataInitializer etc. */
    public void setScannedAt(LocalDateTime scannedAt) {
        this.scannedAt = scannedAt;
    }

    @OneToMany(mappedBy = "scanResult", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OswlComponent> components = new ArrayList<>();

    public void complete() {
        this.status = ScanStatus.COMPLETED;
    }

    public void fail(String message) {
        this.status = ScanStatus.FAILED;
        this.errorMessage = message;
    }
}
