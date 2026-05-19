package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A library whose license could not be confidently classified
 * and therefore requires manual review by a compliance officer.
 */
@Value
@Builder
public class LicenseReviewItemDto {

    /** Raw license string as captured from the package manifest (may be empty). */
    String rawLicense;

    /** Why this entry needs review: "UNKNOWN", "PROPRIETARY", "CUSTOM", "MISSING". */
    String reason;

    /** Short human explanation shown next to the badge. */
    String hint;

    /** Sorted "artifactId version" strings. */
    List<String> libraries;
}
