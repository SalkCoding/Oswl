package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CveRepository extends JpaRepository<Cve, Long> {

    List<Cve> findByComponentId(Long componentId);

    /** 특정 스캔의 심각도별 CVE 수 집계 */
    @Query("""
            SELECT v.severity, COUNT(v)
            FROM Cve v
            WHERE v.component.scanResult.id = :scanResultId
            GROUP BY v.severity
            """)
    List<Object[]> countBySeverityForScan(@Param("scanResultId") Long scanResultId);

    /** AI 요약이 비어있는 CVE 목록 (배치 분석용) */
    @Query("SELECT v FROM Cve v WHERE v.component.scanResult.id = :scanResultId AND v.aiSummary IS NULL")
    List<Cve> findUnanalyzedByScanResultId(@Param("scanResultId") Long scanResultId);
}
