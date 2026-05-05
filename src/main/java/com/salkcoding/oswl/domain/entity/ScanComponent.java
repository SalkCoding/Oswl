package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Join entity between a ScanResult and a Library.
 * Holds scan-context data (reviewed, ignored, dependencyInfo) while the
 * actual CVE and license data lives on the shared Library entity.
 *
 * {@link DependencyPath} rows capture the full resolved dependency path trees
 * sent by the CLI, enabling the detail panel to render a proper dependency tree.
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
     * Human-readable dependency summary, e.g. "Direct (2) + Transitive (5)"
     * Populated by the CLI at scan time for quick display.
     */
    @Column(name = "dependency_info", length = 300)
    private String dependencyInfo;

    @Column(nullable = false)
    @Builder.Default
    private boolean reviewed = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean ignored = false;

    /**
     * Full dependency path trees from root to this library.
     * Populated from the CLI payload; empty for scans from older CLI versions.
     */
    @OneToMany(mappedBy = "scanComponent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("pathIndex ASC")
    @Builder.Default
    private List<DependencyPath> dependencyPaths = new ArrayList<>();

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
