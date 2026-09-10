package com.salkcoding.oswl.domain.entity.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per logical JVM-local (Caffeine) cache. The owning service bumps {@code version}
 * in the same transaction as a settings mutation; every instance polls this table
 * (see {@code CacheInvalidationPoller}) and evicts its local cache on a version change,
 * propagating invalidations across instances without extra infrastructure.
 */
@Entity
@Table(name = "cache_invalidation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CacheInvalidation {

    @Id
    @Column(name = "cache_name", length = 100)
    private String cacheName;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
