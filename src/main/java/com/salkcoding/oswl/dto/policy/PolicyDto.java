package com.salkcoding.oswl.dto.policy;

import com.salkcoding.oswl.domain.enums.PolicyScopeType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class PolicyDto {

    Long id;
    PolicyScopeType scope;
    Long scopeId;
    String scopeName;
    String name;
    String description;
    boolean locked;
    boolean enabled;
    String failOnSeverity;
    Boolean failOnKev;
    Double failOnEpss;
    Boolean failOnLicenseViolation;
    Boolean onlyNew;
    Boolean onlyReachable;
    Boolean failOnSecrets;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
