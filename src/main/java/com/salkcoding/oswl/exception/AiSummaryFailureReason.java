package com.salkcoding.oswl.exception;

/** Machine-readable reason when on-demand CVE AI summarization fails. */
public enum AiSummaryFailureReason {
    NOT_CONFIGURED,
    API_KEY_INVALID,
    DAILY_CAP,
    PROVIDER_ERROR,
    PARSE_ERROR,
    UNKNOWN
}
