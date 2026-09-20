package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Supplied revisions must be usable; excluding a rejected record additionally requires a revision. */
public final class NvdLifecycle {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public enum State { CURRENT, REJECTED, UNVERIFIED }
    private NvdLifecycle() {}

    public static State assess(String id, String evidence) {
        if (evidence == null) return State.CURRENT;
        try {
            var record = JSON.readTree(evidence);
            if (record == null || !record.isObject() || !record.path("id").isTextual()
                    || !record.path("id").asText().equals(id)) return State.UNVERIFIED;
            if (record.has("conflictingRecords")) return State.UNVERIFIED;
            boolean hasRevision = record.has("lastModified");
            if (hasRevision) {
                if (!record.path("lastModified").isTextual()) return State.UNVERIFIED;
                String timestamp = record.path("lastModified").asText();
                Instant modified;
                try { modified = Instant.parse(timestamp); }
                catch (java.time.format.DateTimeParseException noOffset) {
                    modified = LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
                }
                if (modified.isAfter(Instant.now())) return State.UNVERIFIED;
            }
            var status = record.get("vulnStatus");
            if (status == null) return State.CURRENT;
            if (!status.isTextual()) return State.UNVERIFIED;
            if (!"Rejected".equals(status.asText())) return State.CURRENT;
            return hasRevision ? State.REJECTED : State.UNVERIFIED;
        } catch (Exception invalid) {
            return State.UNVERIFIED;
        }
    }
}
