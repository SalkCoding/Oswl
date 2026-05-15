package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** Find a key by its token value (used by the interceptor for authentication) */
    Optional<ApiKey> findByToken(String token);

    /** Find all active keys belonging to a project */
    @Query("SELECT k FROM ApiKey k WHERE k.project.id = :projectId AND k.active = true ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveByProjectId(@Param("projectId") Long projectId);

    /** Find all keys for a project (including inactive ones) */
    List<ApiKey> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    /** Find all keys across all projects (admin use) */
    @EntityGraph(attributePaths = {"project"})
    List<ApiKey> findAllByOrderByCreatedAtDesc();

    /**
     * Find an active CLI key that was issued to a specific user for a specific project.
     * Used by CLI auth to return (or reuse) an existing key instead of creating a new one.
     */
    @Query("SELECT k FROM ApiKey k WHERE k.project.id = :projectId AND k.createdByUserId = :userId AND k.active = true ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveByProjectIdAndCreatedByUserId(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
