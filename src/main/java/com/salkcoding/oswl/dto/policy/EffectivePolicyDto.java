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
}
