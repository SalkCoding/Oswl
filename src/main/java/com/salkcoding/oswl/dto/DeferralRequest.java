package com.salkcoding.oswl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /projects/{projectId}/components/{componentId}/defer payload
 */
@Getter
@NoArgsConstructor
public class DeferralRequest {

    /** Reason code: legal-review | false-positive | wont-fix | temporary | other */
    private String reason;

    /** Free-form text when reason = "other" */
    private String otherText;

    /**
     * Expiry preset: 1-week | 1-month | 3-month | 6-month | custom | indefinite
     */
    private String expiry;

    /** ISO date string used when expiry = "custom" (YYYY-MM-DD) */
    private String customDate;

    /**
     * Deferral scope: "project" (this scan component only) or
     * "all-projects" (all ScanComponent rows referencing the same Library).
     */
    private String scope;

    /** Optional note / PR description text */
    private String prDescription;
}
