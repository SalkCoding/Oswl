package com.salkcoding.oswl.domain.enums;

/** Processing status when a CLI scan is received */
public enum ScanStatus {
    /** CLI is transmitting scan data */
    PENDING,
    /** Package list saved; waiting for vulnerability analysis */
    SCANNING,
    /** Fetching CVE and license data from deps.dev + OSV APIs */
    ANALYZING,
    /** Analysis complete */
    COMPLETED,
    /** Analysis failed due to an error */
    FAILED
}
