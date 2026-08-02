package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface LibraryRepository extends JpaRepository<Library, Long> {

    /** E6: one row per (ecosystem, name, version) for the wanted-list export. */
    interface WantedComponentProjection {
        String getEcosystem();
        String getName();
        String getVersion();
    }

    Optional<Library> findByNameAndVersionAndEcosystem(String name, String version, String ecosystem);

    /**
     * A8: name-level lookup used when inferring/storing a CPE match for C/C++ components.
     * The result set is small in practice (one name may exist in a few ecosystems/versions).
     */
    List<Library> findByName(String name);

    /**
     * E6: streamed (not `List`) so a wanted-list export doesn't hold every library in memory —
     * caller must run this inside a read-only transaction and close the stream.
     */
    @Transactional(readOnly = true)
    @QueryHints(@jakarta.persistence.QueryHint(name = "org.hibernate.fetchSize", value = "500"))
    @Query("SELECT l.ecosystem AS ecosystem, l.name AS name, l.version AS version FROM Library l")
    Stream<WantedComponentProjection> streamWantedComponents();

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
