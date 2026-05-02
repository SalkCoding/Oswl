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

    /** 라이선스 상태별 카운트 */
    @Query("SELECT COUNT(c) FROM OswlComponent c WHERE c.scanResult.id = :scanResultId AND c.licenseStatus = :status")
    long countByScanResultIdAndLicenseStatus(@Param("scanResultId") Long scanResultId,
                                              @Param("status") LicenseStatus status);

    /** 스캔 결과의 전체 컴포넌트 수 */
    long countByScanResultId(Long scanResultId);
}
