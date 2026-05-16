package com.salkcoding.oswl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payload for POST /projects/{projectId}/components/{componentId}/defer
 */
@Getter
@NoArgsConstructor
public class DeferralRequest {

    /** Reason code: legal-review | false-positive | wont-fix | temporary | other */
    private String reason;

    /** Free-text when reason = "other" */
    private String otherText;

    /**
     * Expiry preset: 1-week | 1-month | 3-month | 6-month | custom | indefinite
     */
    private String expiry;

    /** ISO date string (YYYY-MM-DD) used when expiry = "custom" */
    private String customDate;

    /**
     * Deferral scope: "project" (this scan component only) or
     * "all-projects" (all ScanComponent rows that reference the same Library).
     */
    private String scope;

    /** Optional note / PR description text */
    private String prDescription;
}
