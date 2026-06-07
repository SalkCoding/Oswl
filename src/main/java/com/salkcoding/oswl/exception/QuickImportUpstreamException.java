package com.salkcoding.oswl.exception;

import lombok.Getter;

import java.util.List;

/**
 * Indicates the VCS provider could not be reached.
 * {@link #messageKey} is resolved on the client using the page locale.
 */
@Getter
public class QuickImportUpstreamException extends RuntimeException {

    private final String messageKey;
    private final List<String> messageArgs;

    public QuickImportUpstreamException(String messageKey) {
        this(messageKey, List.of());
    }

    public QuickImportUpstreamException(String messageKey, List<String> messageArgs) {
        super(messageKey);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs != null ? messageArgs : List.of();
    }
}
