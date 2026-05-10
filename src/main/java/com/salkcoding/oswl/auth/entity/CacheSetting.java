package com.salkcoding.oswl.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "cache_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CacheSetting {

    @Id
    @Column(name = "cache_key", length = 64)
    private String cacheKey;

    @Column(name = "ttl_seconds", nullable = false)
    private long ttlSeconds;

    @Column(name = "last_cleared_at")
    private LocalDateTime lastClearedAt;

    @Column(name = "last_cleared_by")
    private Long lastClearedBy;
}
