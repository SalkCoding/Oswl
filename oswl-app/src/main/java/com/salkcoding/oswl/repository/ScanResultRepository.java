package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ScanResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScanResultRepository extends JpaRepository<ScanResult, Long> {

    /** List of completed scans for the project (version history) */
    @Query("SELECT s FROM ScanResult s WHERE s.project.id = :projectId AND s.status = 'COMPLETED' ORDER BY s.scannedAt DESC")
    List<ScanResult> findCompletedByProjectId(@Param("projectId") Long projectId);

    /** Find existing scan for a project+version combination (for upsert logic) */
    Optional<ScanResult> findByProjectIdAndVersion(Long projectId, String version);

    /** Most recent N completed scans (for the risk trend chart) */
    @Query(value = """
            SELECT * FROM scan_results
            WHERE project_id = :projectId AND status = 'COMPLETED'
            ORDER BY scanned_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ScanResult> findRecentCompleted(@Param("projectId") Long projectId,
                                         @Param("limit") int limit);

    /** Most recent scan for the project (any status) — used for scan status polling banner */
    @Query("SELECT s FROM ScanResult s WHERE s.project.id = :projectId ORDER BY s.scannedAt DESC LIMIT 1")
    Optional<ScanResult> findLatestByProjectId(@Param("projectId") Long projectId);

    /** All scans for a project in reverse chronological order — used for scan history page */
    @Query("SELECT s FROM ScanResult s WHERE s.project.id = :projectId ORDER BY s.scannedAt DESC")
    List<ScanResult> findAllByProjectIdOrderByScannedAtDesc(@Param("projectId") Long projectId);

    /** Find scan by id scoped to a project — avoids lazy-loading the project association */
    Optional<ScanResult> findByIdAndProjectId(Long id, Long projectId);
}
