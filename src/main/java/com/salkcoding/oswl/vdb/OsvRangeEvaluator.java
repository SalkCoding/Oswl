package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** OSV version-list/range union with explicit uncertainty for unsupported range semantics. */
public final class OsvRangeEvaluator {
    public enum Result { AFFECTED, NOT_AFFECTED, UNKNOWN }

    private OsvRangeEvaluator() { }

    public static Result evaluate(String ecosystem, String version, Set<String> versions, JsonNode ranges) {
        if (version == null || version.isBlank()) return Result.UNKNOWN;
        if (versions != null && versions.contains(version)) return Result.AFFECTED;
        if (ranges == null || ranges.isMissingNode() || ranges.isNull() || (ranges.isArray() && ranges.isEmpty())) {
            return versions != null && !versions.isEmpty() ? Result.NOT_AFFECTED : Result.UNKNOWN;
        }
        if (!ranges.isArray()) return Result.UNKNOWN;
        boolean unknown = false;
        for (JsonNode range : ranges) {
            Comparator<String> comparator;
            if ("SEMVER".equals(range.path("type").asText())) {
                comparator = SemVerVersionComparator::compare;
            } else if (ecosystem != null && ecosystem.startsWith("ALPINE:")
                    && "ECOSYSTEM".equals(range.path("type").asText())) {
                comparator = ApkVersionComparator::compare;
            } else {
                // A commit's lexical ordering cannot establish ancestry in its repository.
                unknown = true;
                continue;
            }
            try {
                if (inRange(version, range.path("events"), comparator)) return Result.AFFECTED;
            } catch (IllegalArgumentException unsupported) {
                unknown = true;
            }
        }
        return unknown ? Result.UNKNOWN : Result.NOT_AFFECTED;
    }

    private static boolean inRange(String version, JsonNode rawEvents, Comparator<String> comparator) {
        comparator.compare(version, version);
        if (!rawEvents.isArray() || rawEvents.isEmpty()) throw new IllegalArgumentException("Missing OSV events");
        List<Event> timeline = new ArrayList<>();
        List<String> limits = new ArrayList<>();
        boolean introduced = false;
        boolean fixed = false;
        boolean lastAffected = false;
        for (JsonNode raw : rawEvents) {
            if (!raw.isObject() || raw.size() != 1) throw new IllegalArgumentException("Invalid OSV event");
            var field = raw.properties().iterator().next();
            String kind = field.getKey();
            if (!Set.of("introduced", "fixed", "last_affected", "limit").contains(kind)
                    || !field.getValue().isTextual()) throw new IllegalArgumentException("Invalid OSV event");
            String value = field.getValue().asText();
            if (!(kind.equals("introduced") && value.equals("0"))
                    && !(kind.equals("limit") && value.equals("*"))) comparator.compare(value, value);
            if (kind.equals("limit")) {
                limits.add(value);
            } else {
                introduced |= kind.equals("introduced");
                fixed |= kind.equals("fixed");
                lastAffected |= kind.equals("last_affected");
                timeline.add(new Event(kind, value));
            }
        }
        if (!introduced || (fixed && lastAffected)) throw new IllegalArgumentException("Invalid OSV timeline");
        if (!limits.isEmpty() && limits.stream().noneMatch(limit -> limit.equals("*") || comparator.compare(version, limit) < 0)) {
            return false;
        }
        // Source event arrays need not be ordered; limits constrain the entire range.
        timeline.sort((a, b) -> {
            if (a.beginning() || b.beginning()) return Boolean.compare(b.beginning(), a.beginning());
            int order = comparator.compare(a.version(), b.version());
            if (order != 0) return order;
            return Boolean.compare(b.kind().equals("introduced"), a.kind().equals("introduced"));
        });
        boolean affected = false;
        for (Event event : timeline) {
            int comparison = event.beginning() ? 1 : comparator.compare(version, event.version());
            if (event.kind().equals("introduced") && comparison >= 0) affected = true;
            else if (event.kind().equals("fixed") && comparison >= 0) affected = false;
            else if (event.kind().equals("last_affected") && comparison > 0) affected = false;
        }
        return affected;
    }

    private record Event(String kind, String version) {
        boolean beginning() { return kind.equals("introduced") && version.equals("0"); }
    }
}
