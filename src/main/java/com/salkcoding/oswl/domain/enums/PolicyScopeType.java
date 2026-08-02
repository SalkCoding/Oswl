package com.salkcoding.oswl.domain.enums;

/**
 * Scope at which a {@link com.salkcoding.oswl.domain.entity.Policy} applies.
 * Exactly one of organization / team / project is set on a given row.
 */
public enum PolicyScopeType {
    ORGANIZATION,
    TEAM,
    PROJECT
}
