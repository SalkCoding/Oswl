package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** A rejected source record is excluded only when its identity and revision are usable. */
public final class NvdLifecycle {
    private static final ObjectMapper JSON = new ObjectMapper();
    public enum State { CURRENT, REJECTED, UNVERIFIED }
    private NvdLifecycle() {}

    public static State assess(String id, String evidence) {
        if (evidence == null) return State.CURRENT;
        try {
            var record = JSON.readTree(evidence);
            if (record == null || !record.isObject() || !record.path("id").isTextual()
                    || !record.path("id").asText().equals(id)) return State.UNVERIFIED;
            if (record.has("conflictingRecords")) return State.UNVERIFIED;
            var status = record.get("vulnStatus");
            if (status == null) return State.CURRENT;
            if (!status.isTextual()) return State.UNVERIFIED;
            if (!"Rejected".equals(status.asText())) return State.CURRENT;
            if (!record.path("lastModified").isTextual()) return State.UNVERIFIED;
            String timestamp = record.path("lastModified").asText();
            Instant modified;
            try { modified = Instant.parse(timestamp); }
            catch (java.time.format.DateTimeParseException noOffset) {
                modified = LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
            }
            return modified.isAfter(Instant.now()) ? State.UNVERIFIED : State.REJECTED;
        } catch (Exception invalid) {
            return State.UNVERIFIED;
        }
    }
}
