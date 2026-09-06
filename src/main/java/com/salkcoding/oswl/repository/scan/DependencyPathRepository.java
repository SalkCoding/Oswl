package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.DependencyPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface DependencyPathRepository extends JpaRepository<DependencyPath, Long> {

    /** Retrieve all paths for a component, ordered by their original index. */
    List<DependencyPath> findByScanComponentIdOrderByPathIndexAsc(Long scanComponentId);

    @Query("SELECT dp FROM DependencyPath dp WHERE dp.scanComponent.scanResult.id = :scanId ORDER BY dp.scanComponent.id, dp.pathIndex")
    List<DependencyPath> findByScanResultId(@Param("scanId") Long scanId);

    /** Delete by scan without materializing every component id or generating a large IN list. */
    @Modifying
    @Query("DELETE FROM DependencyPath dp WHERE dp.scanComponent.id IN (SELECT sc.id FROM ScanComponent sc WHERE sc.scanResult.id = :scanId)")
    void deleteByScanResultId(@Param("scanId") Long scanId);

    /** Bulk delete for scan archiving — must run before the owning components are deleted. */
    @Modifying
    @Query("DELETE FROM DependencyPath dp WHERE dp.scanComponent.id IN :scanComponentIds")
    void deleteByScanComponentIdIn(@Param("scanComponentIds") Collection<Long> scanComponentIds);
}
