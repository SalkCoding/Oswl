package com.salkcoding.oswl.exception;

/**
 * Thrown when an outbound HTTP(S) URL targets a disallowed host (private network, metadata, etc.).
 * Message is safe to show to end users.
 */
public class OutboundUrlBlockedException extends IllegalStateException {

    public OutboundUrlBlockedException(String message) {
        super(message);
    }
}
