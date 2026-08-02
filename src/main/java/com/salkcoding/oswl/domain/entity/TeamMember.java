package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.TeamMemberRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Team membership. Any role grants access to all projects assigned to the team —
 * combined with a direct {@link ProjectMember} row by OR in {@code ProjectAccessService},
 * so existing per-project grants keep working unchanged.
 */
@Entity
@Table(name = "team_members",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_team_members_team_user",
                columnNames = {"team_id", "user_id"}
        ),
        indexes = @Index(name = "idx_team_members_user_id", columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class TeamMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TeamMemberRole role = TeamMemberRole.MEMBER;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public void changeRole(TeamMemberRole role) {
        this.role = role;
    }
}
