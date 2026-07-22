package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
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

    /**
     * Scan with its (LAZY) project fetch-joined — project fields stay readable
     * after the query's own transaction ends (async enrichment runs without an outer tx).
     */
    @Query("SELECT s FROM ScanResult s JOIN FETCH s.project WHERE s.id = :id")
    Optional<ScanResult> findWithProjectById(@Param("id") Long id);

    /**
     * Most recent 15 scans with the given status across all projects (AI insight backfill).
     * NULLS LAST keeps parity with the previous in-memory nullsLast ordering —
     * PostgreSQL would default to NULLS FIRST for DESC.
     */
    @Query("SELECT s FROM ScanResult s WHERE s.status = :status ORDER BY s.scannedAt DESC NULLS LAST LIMIT 15")
    List<ScanResult> findTop15ByStatusOrderByScannedAtDesc(@Param("status") ScanStatus status);
}
