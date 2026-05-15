package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScanComponentRepository extends JpaRepository<ScanComponent, Long> {

    /** All ScanComponents for a given scan (library eagerly loaded via @ManyToOne EAGER) */
    List<ScanComponent> findByScanResultId(Long scanResultId);

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

    /** Count components by their library's license status for a given scan */
    @Query("""
            SELECT COUNT(sc) FROM ScanComponent sc
            WHERE sc.scanResult.id = :scanResultId
              AND sc.library.licenseStatus = :status
            """)
    long countByScanResultIdAndLicenseStatus(@Param("scanResultId") Long scanResultId,
                                              @Param("status") LicenseStatus status);

    long countByScanResultId(Long scanResultId);

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
}
