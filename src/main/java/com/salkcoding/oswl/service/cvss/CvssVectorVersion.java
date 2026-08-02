package com.salkcoding.oswl.service.cvss;

/**
 * The CVSS version a vector string declares, independent of whether OsWL can score it.
 * Lets callers distinguish "not a CVSS vector at all" from "a CVSS v4.0 vector we recognize
 * but don't compute a score for" (see {@link CvssV3Calculator} for why).
 */
public enum CvssVectorVersion {
    V3,
    V4,
    UNKNOWN;

    public static CvssVectorVersion detect(String vector) {
        if (vector == null) {
            return UNKNOWN;
        }
        String trimmed = vector.strip();
        if (trimmed.startsWith("CVSS:3.0/") || trimmed.startsWith("CVSS:3.1/")) {
            return V3;
        }
        if (trimmed.startsWith("CVSS:4.0/")) {
            return V4;
        }
        return UNKNOWN;
    }
}
