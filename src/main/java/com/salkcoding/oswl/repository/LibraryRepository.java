package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Library;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LibraryRepository extends JpaRepository<Library, Long> {

    Optional<Library> findByNameAndVersionAndEcosystem(String name, String version, String ecosystem);

    @Query("SELECT l FROM Library l LEFT JOIN FETCH l.cves WHERE l.id = :id")
    Optional<Library> findByIdWithCves(@Param("id") Long id);

    @Query("""
            SELECT DISTINCT l FROM Library l LEFT JOIN FETCH l.cves
            WHERE l.id IN (
                SELECT sc.library.id FROM ScanComponent sc WHERE sc.scanResult.id = :scanResultId
            )
            """)
    List<Library> findByScanResultIdWithCves(@Param("scanResultId") Long scanResultId);
}
