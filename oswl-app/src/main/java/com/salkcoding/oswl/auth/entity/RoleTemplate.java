package com.salkcoding.oswl.auth.entity;

import com.salkcoding.oswl.auth.enums.Permission;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

@Entity
@Table(name = "role_templates",
        uniqueConstraints = @UniqueConstraint(name = "uq_role_templates_name", columnNames = "name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoleTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_built_in", nullable = false)
    private boolean isBuiltIn;

    @Builder.Default
    @ElementCollection(targetClass = Permission.class, fetch = FetchType.EAGER)
    @CollectionTable(
            name = "role_template_permissions",
            joinColumns = @JoinColumn(name = "role_template_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 64)
    private Set<Permission> permissions = EnumSet.noneOf(Permission.class);

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
