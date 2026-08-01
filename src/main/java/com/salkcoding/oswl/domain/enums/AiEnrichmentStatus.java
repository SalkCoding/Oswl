package com.salkcoding.oswl.domain.enums;

/**
 * AI enrichment progress for a scan, tracked separately from {@link ScanStatus} so a scan can
 * reach {@link ScanStatus#COMPLETED} — and show its CVE/license results — before AI summaries
 * are ready, instead of AI generation blocking the whole scan from finishing.
 */
public enum AiEnrichmentStatus {
    /** No AI provider configured — this scan will never generate AI insights. */
    NOT_APPLICABLE,
    /** AI is configured; enrichment has not started yet. */
    PENDING,
    /** AI enrichment is in progress. */
    RUNNING,
    /** AI enrichment finished (individual steps may still have failed and logged a warning). */
    COMPLETED,
    /** AI enrichment did not finish — the scan's data results are unaffected. */
    FAILED
}
