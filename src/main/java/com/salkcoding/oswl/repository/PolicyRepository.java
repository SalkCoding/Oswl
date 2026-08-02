package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.policy.Policy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PolicyRepository extends JpaRepository<Policy, Long> {

    Optional<Policy> findByOrganizationId(Long organizationId);

    Optional<Policy> findByTeamId(Long teamId);

    Optional<Policy> findByProjectId(Long projectId);

    List<Policy> findAllByOrderByCreatedAtDesc();
}
