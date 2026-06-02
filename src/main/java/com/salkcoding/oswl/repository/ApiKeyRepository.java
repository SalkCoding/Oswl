package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByTokenPrefix(String tokenPrefix);

    List<ApiKey> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    long countByProjectId(Long projectId);

    @EntityGraph(attributePaths = {"project"})
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"project"})
    Optional<ApiKey> findWithProjectById(Long id);

    List<ApiKey> findByLegacyTokenIsNotNull();
}
