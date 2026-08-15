package com.salkcoding.oswl.dto;

/**
 * Server-side mirror of the Security Center table's Alpine {@code filters} object and free-text
 * search/sort state — one field per filter checkbox, same names. Bound from request
 * params on the {@code /rows} endpoint, and also used directly for the initial page load so that
 * load matches exactly what the interactive filter panel would produce with its default toggles.
 */
public record SecurityCenterRowFilterParams(
        String search,
        boolean hideNonRuntime,
        boolean reviewed, boolean nonReviewed,
        boolean ignored, boolean nonIgnored, boolean deferred,
        boolean reachable, boolean notReachable, boolean unknownReachability,
        boolean secCritical, boolean secHigh, boolean secMedium, boolean secLow, boolean secUnknown,
        boolean licRestricted, boolean licCaution, boolean licUnknown, boolean licPermitted,
        boolean patchable, boolean nonPatchable, boolean patchDeprecated, boolean patchOutdated, boolean patchUpToDate,
        String sortMode
) {
    /** Matches the Alpine component's default {@code filters} state on a fresh page load. */
    public static SecurityCenterRowFilterParams initialPageLoad() {
        return new SecurityCenterRowFilterParams(
                null, true,
                false, false,
                false, true, false,
                false, false, false,
                false, false, false, false, false,
                false, false, false, false,
                false, false, false, false, false,
                "risk");
    }
}
