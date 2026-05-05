package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LicensePolicyRepository extends JpaRepository<LicensePolicyEntry, Long> {

    Optional<LicensePolicyEntry> findBySpdxId(String spdxId);

    List<LicensePolicyEntry> findByStatus(LicenseStatus status);
}
