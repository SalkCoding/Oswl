package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Library;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface LibraryRepository extends JpaRepository<Library, Long> {

    Optional<Library> findByNameAndVersionAndEcosystem(String name, String version, String ecosystem);

    /**
     * Bulk lookup for scan ingest: fetches every library whose name is in {@code names} in a
     * single query. Callers filter the result down to exact (name, version, ecosystem) keys in
     * memory — a tuple-IN derived query is not expressible, and the distinct name set of one
     * scan payload is small.
     */
    List<Library> findByNameIn(Collection<String> names);

    @Query("""
            SELECT DISTINCT l FROM Library l LEFT JOIN FETCH l.cves
            WHERE l.id IN (
                SELECT sc.library.id FROM ScanComponent sc WHERE sc.scanResult.id = :scanResultId
            )
            """)
    List<Library> findByScanResultIdWithCves(@Param("scanResultId") Long scanResultId);
}
