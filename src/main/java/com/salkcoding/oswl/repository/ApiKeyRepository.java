package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** Find a key by its token value (used by the interceptor for authentication) */
    Optional<ApiKey> findByToken(String token);

    /** Find all keys for a project (including inactive ones) */
    List<ApiKey> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** Find all keys across all projects (admin use) */
    @EntityGraph(attributePaths = {"project"})
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    /** Find a key by ID with its project eagerly loaded */
    @EntityGraph(attributePaths = {"project"})
    Optional<ApiKey> findWithProjectById(Long id);
}
