package com.salkcoding.oswl.exception;

import lombok.Getter;

@Getter
public class AiSummaryException extends IllegalStateException {

    private final AiSummaryFailureReason code;
    private final String messageKey;
    private final Object[] args;

    public AiSummaryException(AiSummaryFailureReason code, Object... args) {
        super(code.name());
        this.code = code;
        this.messageKey = "componentDetail.cve.aiError." + switch (code) {
            case NOT_CONFIGURED -> "notConfigured";
            case API_KEY_INVALID -> "apiKey";
            case DAILY_CAP -> "dailyCap";
            case PROVIDER_ERROR -> "provider";
            case PARSE_ERROR -> "parse";
            case UNKNOWN -> "unknown";
        };
        this.args = args != null && args.length > 0 ? args : null;
    }
}
