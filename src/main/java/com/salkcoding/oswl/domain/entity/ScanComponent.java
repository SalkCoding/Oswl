package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Join entity between ScanResult and Library.
 * The actual CVE and license data live on the shared Library entity,
 * while scan-context data (reviewed, ignored, dependencyInfo) is stored here.
 *
 * {@link DependencyPath} rows store the full dependency path tree sent by the CLI
 * so the detail panel can render the correct dependency tree.
 */
@Entity
@Table(name = "scan_components",
        indexes = {
            @Index(name = "idx_scan_components_scan", columnList = "scan_result_id"),
            @Index(name = "idx_scan_components_library", columnList = "library_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ScanComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scan_result_id", nullable = false)
    private ScanResult scanResult;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "library_id", nullable = false)
    private Library library;

    /**
     * Human-readable dependency summary. Example: "Direct (2) + Transitive (5)"
     * Populated by the CLI at scan time for faster display.
     */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    // ── Deferral (exception handling) ───────────────────────────────

    /** Timestamp when deferral was confirmed; null = not deferred */
    @Column(name = "deferred_at")
    private LocalDateTime deferredAt;

    /**
     * Reason code: legal-review | false-positive | wont-fix | temporary | other
     */
    @Column(name = "deferral_reason", length = 50)
    private String deferralReason;

    /** Expiration date of this deferral; null = indefinite */
    @Column(name = "deferral_expires_at")
    private LocalDateTime deferralExpiresAt;

    /** Free-form note (PR description or 'other' reason text) */
    @Column(name = "deferral_note", columnDefinition = "TEXT")
    private String deferralNote;

    /**
     * Full dependency path tree from the root to this library.
     * Filled from the CLI payload and empty for scans created by older CLI versions.
     */
    @OneToMany(mappedBy = "scanComponent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pathIndex ASC")
    @Builder.Default
    private List<DependencyPath> dependencyPaths = new ArrayList<>();

    // ── Mutation helpers ─────────────────────────────────────────

    public void markReviewed(boolean reviewed) {
        this.reviewed = reviewed;
    }

    public void markIgnored(boolean ignored) {
        this.ignored = ignored;
    }

    public void applyDeferral(String reason, LocalDateTime expiresAt, String note) {
        this.deferredAt = LocalDateTime.now();
        this.deferralReason = reason;
        this.deferralExpiresAt = expiresAt;
        this.deferralNote = note;
    }

    public boolean isDeferred() {
        return deferredAt != null;
    }

    // ── Convenience delegates to Library ─────────────────────────────────

    public String getName()    { return library.getName(); }
    public String getVersion() { return library.getVersion(); }
}
