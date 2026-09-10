package com.salkcoding.oswl.service.cvss;

/** CVSS numeric bounds shared by live and stored advisory observations. */
public final class CvssScore {
    private CvssScore() {}

    public static Double validOrNull(Double score) {
        return score != null && Double.isFinite(score) && score >= 0 && score <= 10 ? score : null;
    }
}
