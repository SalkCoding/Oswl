package com.salkcoding.oswl.dto.policy;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class EffectivePolicyDto {

    Long projectId;
    PolicyDto organizationPolicy;
    PolicyDto teamPolicy;
    PolicyDto projectPolicy;

    String failOnSeverity;
    boolean failOnKev;
    Double failOnEpss;
    boolean failOnLicenseViolation;
    boolean onlyNew;
    // Boolean (not the primitive the 5 fields above use) — unlike those, which always have an
    // instance-wide default to fall back to before this DTO is built, onlyReachable/failOnSecrets
    // can legitimately be null here (GatePolicyService applies its own default later); a primitive
    // would NPE on unboxing that null.
    Boolean onlyReachable;
    Boolean failOnSecrets;
}
