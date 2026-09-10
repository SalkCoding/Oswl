package com.salkcoding.oswl.domain.entity.snapshot;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Per-source bookkeeping for the air-gapped offline snapshot store:
 * when the source was last imported and how many records it holds.
 *
 * <p>The {@code bundleId}/{@code builtAt}/{@code sourceAsOf}/{@code origin}/
 * {@code formatVersion} columns capture bundle provenance — null on rows imported from a
 * v1 (original) bundle or before this column set existed, since those bundles carried no
 * provenance to record. {@code sourceAsOf} (the upstream data's own as-of date) is what the
 * staleness UI keys off, deliberately distinct from {@code importedAt}/{@code builtAt}.
 */
@Entity
@Table(name = "airgapped_snapshot_meta")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class SnapshotMeta {

    /** Same source namespace as {@link SnapshotEntry#getSource()}. */
    @Id
    @Column(length = 20)
    private String source;

    @Column(name = "record_count", nullable = false)
    private long recordCount;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    /** UUID of the bundle this source's data came from (meta.json {@code bundleId}); null for v1 bundles. */
    @Column(name = "bundle_id", length = 64)
    private String bundleId;

    /** When the bundle was built (meta.json {@code builtAt}) — distinct from {@link #importedAt}. */
    @Column(name = "built_at")
    private LocalDateTime builtAt;

    /** The upstream data's own as-of date (meta.json {@code sources.<source>.asOf}) — what staleness is measured against. */
    @Column(name = "source_as_of")
    private LocalDate sourceAsOf;

    /** Where this source's data came from (meta.json {@code sources.<source>.origin}), e.g. "osv.dev bulk". */
    @Column(name = "origin", length = 100)
    private String origin;

    /** meta.json {@code formatVersion}; null means a v1 (or missing-meta) bundle. */
    @Column(name = "format_version")
    private Integer formatVersion;
}
