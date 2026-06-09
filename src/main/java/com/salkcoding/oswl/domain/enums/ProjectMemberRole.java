package com.salkcoding.oswl.domain.enums;

/**
 * Role of a user within a single project (project-scoped ACL).
 */
public enum ProjectMemberRole {
    /** Full project access including CLI key management */
    ADMIN,
    /** View scans, security center, and submit scans when globally permitted */
    MEMBER
}
