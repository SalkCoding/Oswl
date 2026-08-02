package com.salkcoding.oswl.service.cvss;

import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import com.salkcoding.oswl.service.cvss.CvssV3Calculator.Requirement;

/**
 * Maps a project's {@link DeploymentProfile} and a component's runtime scope to the CVSS
 * Security Requirement metrics (Confidentiality/Integrity/Availability Requirement) used for
 * the Environmental score (ROADMAP A5).
 *
 * <p>CVSS itself defines no standard mapping from "how is this product deployed" to these
 * metrics — that judgment call is explicitly left to the scoring organization. The mapping
 * below is OsWL's own, chosen so the environmental score reads as "how much would this
 * vulnerability actually matter here," not a copy of the base score:
 * <ul>
 *   <li>Non-runtime dependencies (test/dev/build/provided scope) are always {@code LOW} across
 *       the board — they never ship, so their environmental risk is always lower than their
 *       base score, mirroring the runtime-scope noise cut used elsewhere in Security Center.</li>
 *   <li>{@code SAAS} — a vendor-operated, network-facing, multi-tenant service — rates
 *       {@code HIGH} on all three: a breach affects customer data in a system the vendor
 *       directly operates, and downtime is the vendor's own SLA.</li>
 *   <li>{@code ON_PREMISE_DISTRIBUTION} — shipped to many customer environments — rates
 *       {@code HIGH} confidentiality/integrity (wide blast radius if compromised) but only
 *       {@code MEDIUM} availability, since the vendor doesn't operate the runtime and isn't
 *       directly on the hook for uptime.</li>
 *   <li>{@code INTERNAL_TOOL} rates {@code MEDIUM}/{@code MEDIUM}/{@code LOW} — lower external
 *       exploit exposure, and an outage is an internal inconvenience rather than a customer
 *       incident.</li>
 *   <li>{@code COMMERCIAL_PRODUCT} (the default) and no profile set both rate {@code MEDIUM}
 *       across the board — CVSS's own "Not Defined" is defined to behave exactly like
 *       {@code MEDIUM}, so this is the correct neutral default.</li>
 * </ul>
 */
public final class EnvironmentalRequirementMapper {

    private EnvironmentalRequirementMapper() {
    }

    public record Requirements(Requirement confidentiality, Requirement integrity, Requirement availability) {
        static Requirements of(Requirement all) {
            return new Requirements(all, all, all);
        }
    }

    public static Requirements resolve(DeploymentProfile profile, boolean runtimeScope) {
        if (!runtimeScope) {
            return Requirements.of(Requirement.LOW);
        }
        if (profile == null) {
            return Requirements.of(Requirement.MEDIUM);
        }
        return switch (profile) {
            case SAAS -> Requirements.of(Requirement.HIGH);
            case ON_PREMISE_DISTRIBUTION ->
                    new Requirements(Requirement.HIGH, Requirement.HIGH, Requirement.MEDIUM);
            case INTERNAL_TOOL ->
                    new Requirements(Requirement.MEDIUM, Requirement.MEDIUM, Requirement.LOW);
            case COMMERCIAL_PRODUCT -> Requirements.of(Requirement.MEDIUM);
        };
    }
}
