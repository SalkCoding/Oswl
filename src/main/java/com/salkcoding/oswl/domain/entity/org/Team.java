package com.salkcoding.oswl.domain.entity.org;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * A team inside the organization. Teams group projects so access can be granted
 * once per team instead of once per project (see {@code TeamMember}).
 *
 * A team may have one parent team ({@code parent_team_id}); nesting is limited to
 * two levels — a team that already has a parent cannot be used as a parent.
 * Depth is enforced by {@code TeamService}, not by the schema.
 */
@Entity
@Table(name = "teams",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_teams_org_name",
                columnNames = {"organization_id", "name"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 1000)
    private String description;

    /** Optional parent team. Null = top-level team. Max two levels total. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_team_id")
    private Team parent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void update(String name, String description, Team parent) {
        this.name = name;
        this.description = description;
        this.parent = parent;
    }

    /** Detaches this team from its parent (used when the parent team is deleted). */
    public void clearParent() {
        this.parent = null;
    }
}
