package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Per-source bookkeeping for the air-gapped offline snapshot store:
 * when the source was last imported and how many records it holds.
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
}
