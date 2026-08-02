package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface SnapshotEntryRepository extends JpaRepository<SnapshotEntry, Long> {

    /** Batch lookup for offline client fallbacks (one query per source per batch). */
    List<SnapshotEntry> findBySourceAndEntryKeyIn(String source, Collection<String> entryKeys);

    /** All keys of a source — used to load the KEV catalog into memory. */
    @Query("select e.entryKey from SnapshotEntry e where e.source = :source")
    Set<String> findEntryKeysBySource(@Param("source") String source);

    long countBySource(String source);

    /** Bulk delete before re-importing a source (avoids one DELETE per row). */
    @Modifying
    @Query("delete from SnapshotEntry e where e.source = :source")
    void deleteBySource(@Param("source") String source);

    /** E2: removes one key during a MERGE import — backs the {@code "_deleted": true} delta convention. */
    @Modifying
    @Query("delete from SnapshotEntry e where e.source = :source and e.entryKey = :entryKey")
    void deleteBySourceAndEntryKey(@Param("source") String source, @Param("entryKey") String entryKey);
}
