package com.salkcoding.oswl.domain.entity.snapshot;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "snapshot_generations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SnapshotGeneration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "published_at", nullable = false)
    private java.time.LocalDateTime publishedAt;
    @Column(name = "source_metadata", nullable = false, columnDefinition = "TEXT")
    private String sourceMetadata;
}
