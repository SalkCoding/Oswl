package com.salkcoding.oswl.dto.policy;

import com.salkcoding.oswl.domain.enums.PolicyScopeType;

/**
 * Create / update request for a scoped policy.
 */
public record PolicyRequest(
        PolicyScopeType scopeType,
        Long scopeId,
        String name,
        String description,
        boolean locked,
        boolean enabled,
        String failOnSeverity,
        Boolean failOnKev,
        Double failOnEpss,
        Boolean failOnLicenseViolation,
        Boolean onlyNew,
        Boolean onlyReachable,
        Boolean failOnSecrets
) {}
