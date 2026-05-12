package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Cve;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CveRepository extends JpaRepository<Cve, Long> {

    List<Cve> findByLibraryId(Long libraryId);

    /** Count CVEs by severity across all libraries in a given scan */
    @Query("""
            SELECT v.severity, COUNT(v)
            FROM Cve v
            WHERE v.library.id IN (
                SELECT sc.library.id FROM ScanComponent sc WHERE sc.scanResult.id = :scanResultId
            )
            GROUP BY v.severity
            """)
    List<Object[]> countBySeverityForScan(@Param("scanResultId") Long scanResultId);

    /** CVEs without an AI summary for a given library */
    @Query("SELECT v FROM Cve v WHERE v.library.id = :libraryId AND v.aiSummary IS NULL")
    List<Cve> findUnanalyzedByLibraryId(@Param("libraryId") Long libraryId);

    /** Find an existing CVE by GHSA ID under a library (avoids duplicate inserts) */
    java.util.Optional<Cve> findByLibraryIdAndGhsaId(Long libraryId, String ghsaId);
}
