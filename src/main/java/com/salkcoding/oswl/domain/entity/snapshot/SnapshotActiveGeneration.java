package com.salkcoding.oswl.domain.entity.snapshot;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "snapshot_active_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SnapshotActiveGeneration {
    @Id
    private Long id;
    @ManyToOne @JoinColumn(name = "generation_id")
    private SnapshotGeneration generation;
}
