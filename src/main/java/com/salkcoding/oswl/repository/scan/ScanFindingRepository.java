package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ScanFindingRepository extends JpaRepository<ScanFinding, Long> {

    long countByScanResultId(Long scanResultId);

    /** Most severe first (CRITICAL..NONE), regardless of the enum's alphabetical string storage. */
    @Query("""
            SELECT f FROM ScanFinding f
            WHERE f.scanResult.id = :scanResultId
              AND f.scanResult.project.id = :projectId
            ORDER BY CASE f.severity
                WHEN com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL THEN 0
                WHEN com.salkcoding.oswl.domain.enums.RiskLevel.HIGH THEN 1
                WHEN com.salkcoding.oswl.domain.enums.RiskLevel.MEDIUM THEN 2
                WHEN com.salkcoding.oswl.domain.enums.RiskLevel.LOW THEN 3
                ELSE 4
            END, f.filePath ASC
            """)
    List<ScanFinding> findByScanResultIdAndProjectId(@Param("scanResultId") Long scanResultId,
                                                       @Param("projectId") Long projectId);
}
