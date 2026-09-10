package com.salkcoding.oswl.domain.enums;

/**
 * Role of a user within a team (team-scoped ACL).
 *
 * Both roles grant access to every project assigned to the team; the role only
 * marks the team's point of contact. Team structure/membership management itself is
 * governed by the global {@code TEAM_MANAGE} permission (or SYSTEM_ADMIN).
 */
public enum TeamMemberRole {
    /** Designated point of contact for the team */
    LEAD,
    /** Regular team member — access to all projects of the team */
    MEMBER
}
