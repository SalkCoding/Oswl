package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Organization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {

    /** The single organization row of this deployment (created by migration/bootstrap). */
    Optional<Organization> findFirstByOrderByIdAsc();
}
