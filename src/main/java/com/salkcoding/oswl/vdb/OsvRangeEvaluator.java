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

    /** Recheck API membership using all entries for the exact queried package. */
    public static Result evaluateAdvisory(JsonNode advisory, String ecosystem, String name, String version) {
        if (advisory == null || !advisory.path("affected").isArray()
                || ecosystem == null || name == null || name.isBlank()) return Result.UNKNOWN;
        boolean matched = false;
        boolean unknown = false;
        for (JsonNode entry : advisory.path("affected")) {
            JsonNode pkg = entry.path("package");
            if (!pkg.path("ecosystem").isTextual() || !pkg.path("name").isTextual()
                    || pkg.path("ecosystem").asText().isBlank() || pkg.path("name").asText().isBlank()) {
                unknown = true;
                continue;
            }
            if (!ecosystem.equals(pkg.path("ecosystem").asText())) continue;
            try {
                if (!AdvisoryPackageNames.canonical(ecosystem, name)
                        .equals(AdvisoryPackageNames.canonical(ecosystem, pkg.path("name").asText()))) continue;
                matched = true;
                if (entry.has("versions") && !entry.path("versions").isArray()) {
                    unknown = true;
                    continue;
                }
                Set<String> versions = new java.util.LinkedHashSet<>();
                boolean malformed = false;
                for (JsonNode listed : entry.path("versions")) {
                    if (!listed.isTextual() || listed.asText().isBlank()) malformed = true;
                    else versions.add(listed.asText());
                }
                if (malformed) {
                    unknown = true;
                    continue;
                }
                Result result = evaluate(ecosystem, version, versions, entry.path("ranges"));
                if (result == Result.AFFECTED) return result;
                unknown |= result == Result.UNKNOWN;
            } catch (IllegalArgumentException invalidIdentity) {
                unknown = true;
            }
        }
        // No matching package contradicts the API's membership claim, not proof of a clean lookup.
        return matched && !unknown ? Result.NOT_AFFECTED : Result.UNKNOWN;
    }

    public static Result evaluate(String ecosystem, String version, Set<String> versions, JsonNode ranges) {
        if (version == null || version.isBlank()) return Result.UNKNOWN;
        if ("MAVEN".equalsIgnoreCase(ecosystem)) {
            try {
                MavenVersionComparator.validate(version);
            } catch (IllegalArgumentException unresolvedVersion) {
                return Result.UNKNOWN;
            }
        }
        if (versions != null && versions.contains(version)) return Result.AFFECTED;
        boolean unknown = false;
        if ("MAVEN".equalsIgnoreCase(ecosystem) && versions != null) {
            for (String listed : versions) {
                try {
                    MavenVersionComparator.validate(listed);
                } catch (IllegalArgumentException unresolvedVersion) {
                    unknown = true;
                }
            }
        }
        if ("GO".equalsIgnoreCase(ecosystem) && versions != null) {
            for (String listed : versions) {
                try {
                    if (GoVersionComparator.sameVersion(version, listed)) return Result.AFFECTED;
                } catch (IllegalArgumentException invalid) {
                    unknown = true;
                }
            }
        }
        // PyPI release aliases identify the same version. Do not generalize ordering equality
        // to Maven artifacts or SemVer builds, which can contain different code.
        if ("PYPI".equalsIgnoreCase(ecosystem) && versions != null) {
            for (String listed : versions) {
                try {
                    if (Pep440VersionComparator.compare(version, listed) == 0) return Result.AFFECTED;
                } catch (IllegalArgumentException invalid) {
                    unknown = true;
                }
            }
        }
        if (ranges == null || ranges.isMissingNode() || ranges.isNull() || (ranges.isArray() && ranges.isEmpty())) {
            return !unknown && versions != null && !versions.isEmpty() ? Result.NOT_AFFECTED : Result.UNKNOWN;
        }
        if (!ranges.isArray()) return Result.UNKNOWN;
        for (JsonNode range : ranges) {
            Comparator<String> comparator = comparator(ecosystem, range.path("type").asText());
            if (comparator == null) {
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

    static Comparator<String> comparator(String ecosystem, String type) {
        if ("GO".equalsIgnoreCase(ecosystem) && ("SEMVER".equals(type) || "ECOSYSTEM".equals(type))) {
            return GoVersionComparator::compare;
        }
        if ("PYPI".equalsIgnoreCase(ecosystem) && "ECOSYSTEM".equals(type)) {
            return Pep440VersionComparator::compare;
        }
        if ("MAVEN".equalsIgnoreCase(ecosystem) && "ECOSYSTEM".equals(type)) {
            return MavenVersionComparator::compare;
        }
        if ("SEMVER".equals(type) || ("ECOSYSTEM".equals(type) && "NPM".equalsIgnoreCase(ecosystem))) {
            return SemVerVersionComparator::compare;
        }
        if (ecosystem != null && ecosystem.startsWith("ALPINE:") && "ECOSYSTEM".equals(type)) {
            return ApkVersionComparator::compare;
        }
        return null;
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
