package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LicensePolicyRepository extends JpaRepository<LicensePolicyEntry, Long> {

    Optional<LicensePolicyEntry> findBySpdxId(String spdxId);
}
