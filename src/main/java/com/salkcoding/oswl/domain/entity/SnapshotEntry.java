package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One row of the air-gapped offline snapshot store.
 * Payload is a JSON document whose shape depends on the source
 * (see {@code AirgappedSnapshotService}): OSV vuln array, deps.dev version/advisory
 * record, EPSS score, or a KEV marker.
 */
@Entity
@Table(name = "airgapped_snapshot_entries",
        indexes = {
            @Index(name = "idx_snapshot_entries_source_key", columnList = "source, entry_key")
        },
        uniqueConstraints = @UniqueConstraint(
                name = "uq_snapshot_entry_source_key",
                columnNames = {"source", "entry_key"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class SnapshotEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Data source namespace: osv / depsdev-version / depsdev-advisory / epss / kev */
    @Column(nullable = false, length = 20)
    private String source;

    /**
     * Lookup key. For component-scoped sources: "ECOSYSTEM|name|version"
     * (ecosystem normalized to the deps.dev uppercase form). For CVE-scoped
     * sources: the uppercase CVE/GHSA id.
     */
    @Column(name = "entry_key", nullable = false, length = 600)
    private String entryKey;

    /** JSON payload consumed by the offline client fallbacks. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
}
