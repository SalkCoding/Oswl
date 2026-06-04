package com.salkcoding.oswl.exception;

/**
 * Indicates the VCS provider could not be reached; message is safe for API clients (no internal details).
 */
public class QuickImportUpstreamException extends RuntimeException {

    public QuickImportUpstreamException(String safeMessage) {
        super(safeMessage);
    }
}
