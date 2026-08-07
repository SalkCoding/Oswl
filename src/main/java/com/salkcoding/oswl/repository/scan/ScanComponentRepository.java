package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface ScanComponentRepository extends JpaRepository<ScanComponent, Long> {

    /**
     * All ScanComponents for a given scan, with library and its CVEs fetch-joined so the
     * result stays fully usable after the query's own transaction ends (the async
     * enrichment pipeline and the version-diff analyzer run without an outer transaction).
     */
    @Query("""
            SELECT DISTINCT sc FROM ScanComponent sc
            JOIN FETCH sc.library l
            LEFT JOIN FETCH l.cves
            WHERE sc.scanResult.id = :scanResultId
            """)
    List<ScanComponent> findByScanResultId(@Param("scanResultId") Long scanResultId);

    /** Single component with library + CVEs for the detail panel */
    @Query("""
            SELECT sc FROM ScanComponent sc
            JOIN FETCH sc.library l
            LEFT JOIN FETCH l.cves
            WHERE sc.id = :componentId
              AND sc.scanResult.project.id = :projectId
            """)
    Optional<ScanComponent> findByIdAndProjectIdWithCves(@Param("componentId") Long componentId,
                                                          @Param("projectId") Long projectId);

    long countByScanResultId(Long scanResultId);

    /** Component ids only — used to bulk-delete their dependency paths before the components themselves. */
    @Query("SELECT sc.id FROM ScanComponent sc WHERE sc.scanResult.id = :scanResultId")
    List<Long> findIdsByScanResultId(@Param("scanResultId") Long scanResultId);

    /** Bulk delete for scan archiving — bypasses cascade, so dependency paths must be deleted first. */
    @Modifying
    @Query("DELETE FROM ScanComponent sc WHERE sc.scanResult.id = :scanResultId")
    void deleteByScanResultId(@Param("scanResultId") Long scanResultId);

    /**
     * Component counts for many scans in one query (scan history page, avoids per-row N+1).
     * LEFT JOIN from ScanResult so scans with zero components are still returned with count 0.
     */
    @Query("""
            SELECT sr.id, COUNT(sc) FROM ScanResult sr
            LEFT JOIN sr.components sc
            WHERE sr.id IN :scanResultIds
            GROUP BY sr.id
            """)
    List<Object[]> countComponentsByScanResultIds(@Param("scanResultIds") List<Long> scanResultIds);

    /** Components whose deferral expired recently — drives the Security Center re-review reminder. */
    @Query("""
            SELECT COUNT(sc) FROM ScanComponent sc
            WHERE sc.scanResult.id = :scanResultId
              AND sc.deferralExpiredAt >= :since
            """)
    long countRecentlyExpiredDeferrals(@Param("scanResultId") Long scanResultId,
                                       @Param("since") LocalDateTime since);

    /** Bulk-load components by ID list, restricted to a specific project (prevents IDOR). */
    @Query("""
            SELECT sc FROM ScanComponent sc
            WHERE sc.id IN :ids
              AND sc.scanResult.project.id = :projectId
            """)
    List<ScanComponent> findAllByIdInAndProjectId(@Param("ids") List<Long> ids,
                                                   @Param("projectId") Long projectId);

    /** Count distinct projects referencing each library (in completed scans across the workspace) */
    @Query("""
            SELECT sc.library.id, COUNT(DISTINCT sc.scanResult.project.id)
            FROM ScanComponent sc
            WHERE sc.library.id IN :libraryIds
              AND sc.scanResult.status = 'COMPLETED'
            GROUP BY sc.library.id
            """)
    List<Object[]> countDistinctProjectsByLibraryIds(@Param("libraryIds") List<Long> libraryIds);

    /** Count distinct projects that include a specific library (in completed scans) */
    @Query("""
            SELECT COUNT(DISTINCT sc.scanResult.project.id)
            FROM ScanComponent sc
            WHERE sc.library.id = :libraryId
              AND sc.scanResult.status = 'COMPLETED'
            """)
    long countDistinctProjectsByLibraryId(@Param("libraryId") Long libraryId);

    /**
     * Global search: component name matches inside the latest completed scan of each accessible
     * project. Rows are {@code [projectId, projectName, name, version, ecosystem]}; the caller
     * caps results via the pageable.
     */
    @Query("""
            SELECT DISTINCT p.id, p.name, l.name, l.version, l.ecosystem
            FROM ScanComponent sc
            JOIN sc.library l
            JOIN sc.scanResult sr
            JOIN sr.project p
            WHERE sr.id = (SELECT MAX(s2.id) FROM ScanResult s2
                           WHERE s2.project = p AND s2.status = 'COMPLETED')
              AND p.id IN :projectIds
              AND LOWER(l.name) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY l.name
            """)
    List<Object[]> searchAccessibleComponents(@Param("projectIds") Collection<Long> projectIds,
                                              @Param("q") String q,
                                              Pageable pageable);

    /** All ScanComponents referencing a given library within the given projects — used for access-scoped cross-project deferral */
    @Query("""
            SELECT sc FROM ScanComponent sc
            WHERE sc.library.id = :libraryId
              AND sc.scanResult.project.id IN :projectIds
            """)
    List<ScanComponent> findAllByLibraryIdAndProjectIdIn(@Param("libraryId") Long libraryId,
                                                         @Param("projectIds") List<Long> projectIds);

    /** All ScanComponents with an expired deferral (for the nightly expiry scheduler) */
    @Query("""
            SELECT sc FROM ScanComponent sc
            WHERE sc.deferredAt IS NOT NULL
              AND sc.deferralExpiresAt IS NOT NULL
              AND sc.deferralExpiresAt <= :now
            """)
    List<ScanComponent> findExpiredDeferrals(@Param("now") LocalDateTime now);

    /**
     * Deferrals that will expire within the given window and have not already expired.
     * Used by the webhook notification scheduler to warn about imminent waivers.
     */
    @Query("""
            SELECT sc FROM ScanComponent sc
            WHERE sc.deferredAt IS NOT NULL
              AND sc.deferralExpiresAt IS NOT NULL
              AND sc.deferralExpiresAt > :now
              AND sc.deferralExpiresAt <= :windowEnd
            """)
    List<ScanComponent> findDeferralsExpiringWithin(@Param("now") LocalDateTime now,
                                                    @Param("windowEnd") LocalDateTime windowEnd);
}
