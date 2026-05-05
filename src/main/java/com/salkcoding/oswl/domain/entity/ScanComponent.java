package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Join entity between a ScanResult and a Library.
 * Holds scan-context data (reviewed, ignored, dependencyInfo) while the
 * actual CVE and license data lives on the shared Library entity.
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
     * Human-readable dependency path, e.g. "Direct (2) + Transitive (5)"
     * Populated by the CLI at scan time.
     */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    // ── Mutation helpers ─────────────────────────────────────────────────

    public void markReviewed(boolean reviewed) {
        this.reviewed = reviewed;
    }

    public void markIgnored(boolean ignored) {
        this.ignored = ignored;
    }

    // ── Convenience delegates to Library ─────────────────────────────────

    public String getName()    { return library.getName(); }
    public String getVersion() { return library.getVersion(); }
}
