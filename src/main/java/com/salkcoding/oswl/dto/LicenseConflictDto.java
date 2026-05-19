package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A potential license incompatibility detected between two licenses
 * that co-exist in the same scan result.
 */
@Value
@Builder
public class LicenseConflictDto {

    /** SPDX id of the more-restrictive side of the conflict. */
    String licenseA;

    /** SPDX id of the other side. */
    String licenseB;

    /** "HIGH" / "MEDIUM" / "LOW" — severity of the conflict. */
    String severity;

    /** Short title (e.g. "GPL-2.0 incompatible with Apache-2.0 patent clause"). */
    String title;

    /** One-paragraph human explanation of why it conflicts. */
    String explanation;

    /** Suggested action (e.g. "Replace with Apache-2.0 alternative, or upgrade to GPL-3.0"). */
    String recommendation;

    /** Affected libraries on the A side. */
    List<String> librariesA;

    /** Affected libraries on the B side. */
    List<String> librariesB;
}
