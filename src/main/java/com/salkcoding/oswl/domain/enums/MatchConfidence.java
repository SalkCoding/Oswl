package com.salkcoding.oswl.domain.enums;

/**
 * Confidence of the match between a component name and a vulnerability identifier.
 * Used primarily for CPE-based NVD lookups, where a naive name→vendor/product mapping
 * can easily produce false positives.
 */
public enum MatchConfidence {
    HIGH,
    MEDIUM,
    LOW
}
