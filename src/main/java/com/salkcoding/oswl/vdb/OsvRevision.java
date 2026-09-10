package com.salkcoding.oswl.vdb;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Revision timestamps must be usable before source evidence can establish coverage. */
public final class OsvRevision {
    private OsvRevision() {}

    public static boolean isCurrent(Object value) {
        if (!(value instanceof String timestamp) || timestamp.length() > 64) return false;
        try {
            return !Instant.parse(timestamp).isAfter(Instant.now());
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
