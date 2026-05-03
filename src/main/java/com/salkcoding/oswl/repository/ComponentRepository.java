package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

public interface ComponentRepository extends JpaRepository<OswlComponent, Long>,
        QuerydslPredicateExecutor<OswlComponent> {

    List<OswlComponent> findByScanResultId(Long scanResultId);

    Optional<OswlComponent> findByIdAndScanResultProjectId(Long componentId, Long projectId);

    /** Fetch components with their CVEs — prevents N+1 queries */
    @Query("SELECT c FROM OswlComponent c LEFT JOIN FETCH c.cves WHERE c.scanResult.id = :scanResultId")
    List<OswlComponent> findByScanResultIdWithCves(@Param("scanResultId") Long scanResultId);

    /** Fetch a single component with its CVEs — for the component detail page */
    @Query("SELECT c FROM OswlComponent c LEFT JOIN FETCH c.cves WHERE c.id = :componentId AND c.scanResult.project.id = :projectId")
    Optional<OswlComponent> findByIdAndProjectIdWithCves(@Param("componentId") Long componentId,
                                                          @Param("projectId") Long projectId);

    /** Count components by license status */
    @Query("SELECT COUNT(c) FROM OswlComponent c WHERE c.scanResult.id = :scanResultId AND c.licenseStatus = :status")
    long countByScanResultIdAndLicenseStatus(@Param("scanResultId") Long scanResultId,
                                              @Param("status") LicenseStatus status);

    /** Count all components for a scan result */
    long countByScanResultId(Long scanResultId);
}
