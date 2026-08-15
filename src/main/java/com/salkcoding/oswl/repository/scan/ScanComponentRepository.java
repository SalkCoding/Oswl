package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import org.springframework.data.domain.Page;
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
     * {@code Cve.sources} is an EAGER element collection — without the explicit fetch join
     * Hibernate issues one extra SELECT per CVE to populate it.
     */
    @Query("""
            SELECT DISTINCT sc FROM ScanComponent sc
            JOIN FETCH sc.library l
            LEFT JOIN FETCH l.cves c
            LEFT JOIN FETCH c.sources
            WHERE sc.scanResult.id = :scanResultId
            """)
    List<ScanComponent> findByScanResultId(@Param("scanResultId") Long scanResultId);

    /**
     * Single component with library + CVEs for the detail panel. Also fetch-joins
     * {@code Cve.sources} (EAGER element collection — one SELECT per CVE otherwise) and the
     * LAZY {@code scanResult} association the detail page reads for the project version.
     */
    @Query("""
            SELECT sc FROM ScanComponent sc
            JOIN FETCH sc.library l
            LEFT JOIN FETCH l.cves c
            LEFT JOIN FETCH c.sources
            JOIN FETCH sc.scanResult
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

    /**
     * Bulk-load components by ID list, restricted to a specific project (prevents IDOR).
     * {@code library} is fetch-joined because it's an EAGER association — without the join
     * Hibernate still loads it, but as one secondary SELECT per row.
     */
    @Query("""
            SELECT sc FROM ScanComponent sc
            JOIN FETCH sc.library
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

    /**
     * Server-side filtered + paginated Security Center table query (ROADMAP C4). Mirrors, one
     * filter group at a time, the client-side {@code rowVisible()} predicate that used to run in
     * the browser against every row's {@code data-*} attributes — that approach meant shipping
     * and DOM-rendering all 5,000+ rows up front just so JS could hide most of them. Each boolean
     * pair below is a "no filters in this group active" escape hatch (matches
     * {@code anyFilter}/{@code anyReachability}/etc. in the old JS) followed by the OR'd
     * per-option predicates. Every enum comparison uses the fully-qualified constant because
     * JPQL has no bind-parameter syntax for enum literals.
     */
    @Query("""
            SELECT sc FROM ScanComponent sc JOIN sc.library l
            WHERE sc.scanResult.id = :scanId
              AND (:search IS NULL OR :search = '' OR LOWER(CONCAT(l.name, ' ', l.version)) LIKE LOWER(CONCAT('%', :search, '%')))
              AND (:hideNonRuntime = FALSE OR sc.scope IS NULL OR LOWER(sc.scope) IN ('runtime','compile','import'))
              AND ( (:reviewedF = FALSE AND :nonReviewedF = FALSE)
                 OR (:reviewedF = TRUE AND :nonReviewedF = TRUE)
                 OR (:reviewedF = TRUE AND :nonReviewedF = FALSE AND sc.reviewed = TRUE)
                 OR (:reviewedF = FALSE AND :nonReviewedF = TRUE AND sc.reviewed = FALSE) )
              AND ( (:ignoredF = FALSE AND :nonIgnoredF = FALSE AND :deferredF = FALSE)
                 OR (:deferredF = TRUE AND sc.deferredAt IS NOT NULL)
                 OR (:ignoredF = TRUE AND sc.ignored = TRUE AND sc.deferredAt IS NULL)
                 OR (:nonIgnoredF = TRUE AND sc.ignored = FALSE AND sc.deferredAt IS NULL) )
              AND ( (:reachableF = FALSE AND :notReachableF = FALSE AND :unknownReachF = FALSE)
                 OR (:reachableF = TRUE AND sc.reachability = com.salkcoding.oswl.domain.enums.Reachability.REACHABLE)
                 OR (:notReachableF = TRUE AND sc.reachability = com.salkcoding.oswl.domain.enums.Reachability.NOT_REACHABLE)
                 OR (:unknownReachF = TRUE AND sc.reachability = com.salkcoding.oswl.domain.enums.Reachability.UNKNOWN) )
              AND ( (:secCriticalF = FALSE AND :secHighF = FALSE AND :secMediumF = FALSE AND :secLowF = FALSE AND :secUnknownF = FALSE)
                 OR EXISTS (SELECT 1 FROM Cve cv WHERE cv.library = l AND (
                        (:secCriticalF = TRUE AND cv.severity = com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL) OR
                        (:secHighF = TRUE AND cv.severity = com.salkcoding.oswl.domain.enums.RiskLevel.HIGH) OR
                        (:secMediumF = TRUE AND cv.severity = com.salkcoding.oswl.domain.enums.RiskLevel.MEDIUM) OR
                        (:secLowF = TRUE AND cv.severity = com.salkcoding.oswl.domain.enums.RiskLevel.LOW) OR
                        (:secUnknownF = TRUE AND cv.severity = com.salkcoding.oswl.domain.enums.RiskLevel.NONE)
                 )) )
              AND ( (:licRestrictedF = FALSE AND :licCautionF = FALSE AND :licUnknownF = FALSE AND :licPermittedF = FALSE)
                 OR (:licRestrictedF = TRUE AND l.licenseStatus = com.salkcoding.oswl.domain.enums.LicenseStatus.RESTRICTED)
                 OR (:licCautionF = TRUE AND l.licenseStatus = com.salkcoding.oswl.domain.enums.LicenseStatus.CAUTION)
                 OR (:licUnknownF = TRUE AND l.licenseStatus = com.salkcoding.oswl.domain.enums.LicenseStatus.UNKNOWN)
                 OR (:licPermittedF = TRUE AND l.licenseStatus = com.salkcoding.oswl.domain.enums.LicenseStatus.PERMITTED) )
              AND ( (:patchableF = FALSE AND :nonPatchableF = FALSE AND :patchDeprecatedF = FALSE AND :patchOutdatedF = FALSE AND :patchUpToDateF = FALSE)
                 OR (:patchableF = TRUE
                     AND EXISTS (SELECT 1 FROM Cve cv2 WHERE cv2.library = l AND cv2.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE)
                     AND EXISTS (SELECT 1 FROM Cve cv3 WHERE cv3.library = l AND cv3.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE AND cv3.fixVersion IS NOT NULL AND TRIM(cv3.fixVersion) <> ''))
                 OR (:nonPatchableF = TRUE
                     AND EXISTS (SELECT 1 FROM Cve cv4 WHERE cv4.library = l AND cv4.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE)
                     AND NOT EXISTS (SELECT 1 FROM Cve cv5 WHERE cv5.library = l AND cv5.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE AND cv5.fixVersion IS NOT NULL AND TRIM(cv5.fixVersion) <> ''))
                 OR (:patchDeprecatedF = TRUE AND l.deprecated IS NOT NULL)
                 OR (:patchOutdatedF = TRUE
                     AND NOT EXISTS (SELECT 1 FROM Cve cv6 WHERE cv6.library = l AND cv6.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE)
                     AND l.deprecated IS NULL AND l.isLatestVersion = FALSE)
                 OR (:patchUpToDateF = TRUE
                     AND NOT EXISTS (SELECT 1 FROM Cve cv7 WHERE cv7.library = l AND cv7.severity <> com.salkcoding.oswl.domain.enums.RiskLevel.NONE)
                     AND l.deprecated IS NULL AND l.isLatestVersion = TRUE) )
            """)
    Page<ScanComponent> searchForSecurityCenter(
            @Param("scanId") Long scanId,
            @Param("search") String search,
            @Param("hideNonRuntime") boolean hideNonRuntime,
            @Param("reviewedF") boolean reviewedF,
            @Param("nonReviewedF") boolean nonReviewedF,
            @Param("ignoredF") boolean ignoredF,
            @Param("nonIgnoredF") boolean nonIgnoredF,
            @Param("deferredF") boolean deferredF,
            @Param("reachableF") boolean reachableF,
            @Param("notReachableF") boolean notReachableF,
            @Param("unknownReachF") boolean unknownReachF,
            @Param("secCriticalF") boolean secCriticalF,
            @Param("secHighF") boolean secHighF,
            @Param("secMediumF") boolean secMediumF,
            @Param("secLowF") boolean secLowF,
            @Param("secUnknownF") boolean secUnknownF,
            @Param("licRestrictedF") boolean licRestrictedF,
            @Param("licCautionF") boolean licCautionF,
            @Param("licUnknownF") boolean licUnknownF,
            @Param("licPermittedF") boolean licPermittedF,
            @Param("patchableF") boolean patchableF,
            @Param("nonPatchableF") boolean nonPatchableF,
            @Param("patchDeprecatedF") boolean patchDeprecatedF,
            @Param("patchOutdatedF") boolean patchOutdatedF,
            @Param("patchUpToDateF") boolean patchUpToDateF,
            Pageable pageable);
}
