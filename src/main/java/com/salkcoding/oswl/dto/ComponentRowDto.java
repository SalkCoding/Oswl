package com.salkcoding.oswl.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class ComponentRowDto {
    private final Long id;
    private final String name;
    private final String version;
    private final String dependencyInfo;

    private final boolean reviewed;

    private final int securityCritical;
    private final int securityHigh;
    private final int securityMedium;
    private final int securityLow;

    /** patchable | non-patchable | unknown */
    private final String patchability;

    /** OK / WARN / VIOLATION */
    private final String licenseStatus;
    private final String licenseName;
}
