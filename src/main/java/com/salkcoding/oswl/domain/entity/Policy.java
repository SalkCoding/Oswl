package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.PolicyScopeType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Policy-as-Code row for the security gate. Policies are attached to exactly one scope
 * (organization, team, or project) and compose hierarchically: organization → team → project.
 * A locked policy prevents lower levels from overriding the fields it defines.
 */
@Entity
@Table(name = "policies",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_policies_organization", columnNames = {"organization_id"}),
                @UniqueConstraint(name = "uq_policies_team", columnNames = {"team_id"}),
                @UniqueConstraint(name = "uq_policies_project", columnNames = {"project_id"})
        },
        indexes = {
                @Index(name = "idx_policies_scope", columnList = "scope")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder(toBuilder = true)
@AllArgsConstructor
public class Policy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PolicyScopeType scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id")
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean locked = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "fail_on_severity", length = 20)
    private String failOnSeverity;

    @Column(name = "fail_on_kev")
    private Boolean failOnKev;

    @Column(name = "fail_on_epss")
    private Double failOnEpss;

    @Column(name = "fail_on_license_violation")
    private Boolean failOnLicenseViolation;

    @Column(name = "only_new")
    private Boolean onlyNew;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void update(String name, String description, boolean locked, boolean enabled,
                       String failOnSeverity, Boolean failOnKev, Double failOnEpss,
                       Boolean failOnLicenseViolation, Boolean onlyNew) {
        this.name = name;
        this.description = description;
        this.locked = locked;
        this.enabled = enabled;
        this.failOnSeverity = failOnSeverity;
        this.failOnKev = failOnKev;
        this.failOnEpss = failOnEpss;
        this.failOnLicenseViolation = failOnLicenseViolation;
        this.onlyNew = onlyNew;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
