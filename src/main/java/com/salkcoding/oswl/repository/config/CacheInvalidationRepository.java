package com.salkcoding.oswl.repository.config;

import com.salkcoding.oswl.domain.entity.config.CacheInvalidation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface CacheInvalidationRepository extends JpaRepository<CacheInvalidation, String> {

    /**
     * Atomic increment — safe under concurrent instances (no read-modify-write race,
     * no reliance on clock monotonicity across nodes). Returns 0 when the row does
     * not exist yet; callers then insert it.
     */
    @Modifying
    @Query("update CacheInvalidation c set c.version = c.version + 1, c.updatedAt = :now"
            + " where c.cacheName = :cacheName")
    int incrementVersion(@Param("cacheName") String cacheName, @Param("now") LocalDateTime now);
}
