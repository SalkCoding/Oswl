package com.salkcoding.oswl.domain.entity.org;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * The organization that owns all teams and projects.
 *
 * OsWL currently runs as a single-organization deployment: exactly one row exists,
 * created by the schema migration or by {@code TeamBootstrapRunner} on first boot.
 * The entity still exists as a real table so a future multi-org rollout is a data
 * change, not a schema change.
 */
@Entity
@Table(name = "organizations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void rename(String name) {
        this.name = name;
    }
}
