package com.salkcoding.oswl.domain.entity.snapshot;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "snapshot_generation_entries", uniqueConstraints = @UniqueConstraint(columnNames = {"generation_id", "source", "entry_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SnapshotGenerationEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false) @JoinColumn(name = "generation_id", nullable = false)
    private SnapshotGeneration generation;
    @Column(nullable = false, length = 20)
    private String source;
    @Column(name = "entry_key", nullable = false, length = 600)
    private String entryKey;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
}
