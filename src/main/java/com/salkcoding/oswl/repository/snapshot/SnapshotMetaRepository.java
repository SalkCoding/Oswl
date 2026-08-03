package com.salkcoding.oswl.repository.snapshot;

import com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SnapshotMetaRepository extends JpaRepository<SnapshotMeta, String> {
}
