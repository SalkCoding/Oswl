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

    /** A revision orders observations only when the entire lifecycle evidence is usable. */
    public static java.util.Optional<Instant> revision(String id, String evidence) {
        if (evidence == null || assess(id, evidence) == State.UNVERIFIED) return java.util.Optional.empty();
        try {
            var timestamp = JSON.readTree(evidence).get("lastModified");
            if (timestamp == null || !timestamp.isTextual()) return java.util.Optional.empty();
            return java.util.Optional.of(parseRevision(timestamp.asText()));
        } catch (Exception invalid) {
            return java.util.Optional.empty();
        }
    }

    private static Instant parseRevision(String timestamp) {
        try { return Instant.parse(timestamp); }
        catch (java.time.format.DateTimeParseException noOffset) {
            return LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC);
        }
    }

    /** Protect an already versioned observation from an older or unorderable replacement. */
    public static boolean canReplaceStoredEvidence(String id, String stored, String incoming) {
        var previous = revision(id, stored);
        if (previous.isEmpty()) return true;
        var next = revision(id, incoming);
        if (next.isEmpty() || next.get().isBefore(previous.get())) return false;
        if (next.get().isAfter(previous.get())) return true;
        try {
            var previousRecord = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(stored);
            var nextRecord = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(incoming);
            previousRecord.remove("lastModified");
            nextRecord.remove("lastModified");
            return previousRecord.equals(nextRecord);
        } catch (Exception invalid) {
            return false;
        }
    }

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
                Instant modified = parseRevision(timestamp);
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
