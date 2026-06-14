package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Cve;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CveRepository extends JpaRepository<Cve, Long> {

    /** Find an existing CVE by GHSA ID under a library (avoids duplicate inserts) */
    Optional<Cve> findByLibraryIdAndGhsaId(Long libraryId, String ghsaId);
}
