package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ScanResultRepository extends JpaRepository<ScanResult, Long> {

    /** List of completed scans for the project (version history) */
    @Query("SELECT s FROM ScanResult s WHERE s.project.id = :projectId AND s.status = 'COMPLETED' ORDER BY s.scannedAt DESC")
    List<ScanResult> findCompletedByProjectId(@Param("projectId") Long projectId);

    /**
     * All completed scans for many projects in one query (org dashboard) — ordered per project
     * by scannedAt DESC, so grouping the rows by project id in encounter order reproduces exactly
     * what calling {@link #findCompletedByProjectId(Long)} once per project would return.
     */
    @Query("""
            SELECT s FROM ScanResult s
            WHERE s.project.id IN :projectIds AND s.status = 'COMPLETED'
            ORDER BY s.project.id, s.scannedAt DESC
            """)
    List<ScanResult> findCompletedByProjectIdIn(@Param("projectIds") Collection<Long> projectIds);

    /**
     * Most recent scan (any status) per project, batched for the project list page — one query
     * for every project instead of calling {@link #findLatestByProjectId(Long)} once per project.
     * A tie on {@code scannedAt} within the same project can return more than one row; the caller
     * de-dupes by keeping the first row seen per project id (matches this method's own lack of
     * ordering guarantee across ties, same as the single-project query it replaces).
     */
    @Query("""
            SELECT s FROM ScanResult s
            WHERE s.project.id IN :projectIds
              AND s.scannedAt = (SELECT MAX(s2.scannedAt) FROM ScanResult s2 WHERE s2.project.id = s.project.id)
            """)
    List<ScanResult> findLatestByProjectIds(@Param("projectIds") Collection<Long> projectIds);

    /**
     * Batched fetch of scans with their components and each component's (EAGER) library
     * pre-loaded, so aggregating security/license counts across many scans doesn't re-trigger
     * the components query and the per-component library lookup for every scan.
     */
    @Query("SELECT DISTINCT s FROM ScanResult s LEFT JOIN FETCH s.components c LEFT JOIN FETCH c.library WHERE s.id IN :scanIds")
    List<ScanResult> findByIdInWithComponentsAndLibrary(@Param("scanIds") Collection<Long> scanIds);

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
