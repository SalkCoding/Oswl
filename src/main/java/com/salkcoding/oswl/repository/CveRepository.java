package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CveRepository extends JpaRepository<Cve, Long> {

    List<Cve> findByComponentId(Long componentId);

    /** Count CVEs by severity for a given scan */
    @Query("""
            SELECT v.severity, COUNT(v)
            FROM Cve v
            WHERE v.component.scanResult.id = :scanResultId
            GROUP BY v.severity
            """)
    List<Object[]> countBySeverityForScan(@Param("scanResultId") Long scanResultId);

    /** List of CVEs without an AI summary (for batch analysis) */
    @Query("SELECT v FROM Cve v WHERE v.component.scanResult.id = :scanResultId AND v.aiSummary IS NULL")
    List<Cve> findUnanalyzedByScanResultId(@Param("scanResultId") Long scanResultId);
}
