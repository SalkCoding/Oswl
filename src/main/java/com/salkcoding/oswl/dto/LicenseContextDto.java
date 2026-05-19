package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Deployment context that affects which license obligations are triggered.
 * Sent as URL query parameters from the License page and applied as a filter
 * over the master obligation rule set.
 */
@Value
@Builder
public class LicenseContextDto {

    /** SAAS | BINARY | LIBRARY | EMBEDDED — distribution model. */
    String deployment;

    /** True when the team has modified the upstream source of any OSS component. */
    boolean modified;

    /** DYNAMIC | STATIC — relevant primarily for LGPL relinking obligation. */
    String linking;

    /** Convenience: is this project distributed to third parties? */
    public boolean isDistributed() {
        return !"SAAS".equalsIgnoreCase(deployment);
    }

    public boolean isEmbedded() {
        return "EMBEDDED".equalsIgnoreCase(deployment);
    }

    public boolean isStaticLinking() {
        return "STATIC".equalsIgnoreCase(linking);
    }
}
