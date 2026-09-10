package com.salkcoding.oswl.service.cvss;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parsed CVSS v3.0/v3.1 base metrics from a vector string
 * (e.g. {@code CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H}).
 *
 * <p>Only the eight Base metrics are modeled — OsWL does not collect Temporal or the
 * attacker-facing Modified* metrics from any data source, so those stay at their CVSS-defined
 * "Not Defined" (neutral) value everywhere they would apply.
 */
record CvssV3Vector(String av, String ac, String pr, String ui, String scope, String c, String i, String a) {

    private static final Set<String> REQUIRED_METRICS = Set.of("AV", "AC", "PR", "UI", "S", "C", "I", "A");

    /**
     * @param vector a full vector string, with or without the {@code CVSS:3.x/} prefix
     * @return the parsed metrics, or {@code null} if the vector is not a well-formed CVSS v3
     *         base vector (missing/unknown metric, or not a v3.0/v3.1 vector at all)
     */
    static CvssV3Vector parse(String vector) {
        if (vector == null || vector.isBlank()) {
            return null;
        }
        String trimmed = vector.strip();
        if (!trimmed.startsWith("CVSS:3.0/") && !trimmed.startsWith("CVSS:3.1/")) {
            return null;
        }
        String body = trimmed.substring(trimmed.indexOf('/') + 1);
        Map<String, String> metrics = new HashMap<>();
        for (String part : body.split("/")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) {
                return null;
            }
            metrics.put(kv[0], kv[1]);
        }
        if (!metrics.keySet().containsAll(REQUIRED_METRICS)) {
            return null;
        }
        try {
            CvssV3Vector parsed = new CvssV3Vector(
                    metrics.get("AV"), metrics.get("AC"), metrics.get("PR"), metrics.get("UI"),
                    metrics.get("S"), metrics.get("C"), metrics.get("I"), metrics.get("A"));
            parsed.validate();
            return parsed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void validate() {
        requireOneOf("AV", av, "N", "A", "L", "P");
        requireOneOf("AC", ac, "L", "H");
        requireOneOf("PR", pr, "N", "L", "H");
        requireOneOf("UI", ui, "N", "R");
        requireOneOf("S", scope, "U", "C");
        requireOneOf("C", c, "N", "L", "H");
        requireOneOf("I", i, "N", "L", "H");
        requireOneOf("A", a, "N", "L", "H");
    }

    private static void requireOneOf(String metric, String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException("Invalid CVSS v3 metric " + metric + ": " + value);
    }

    boolean scopeChanged() {
        return "C".equals(scope);
    }
}
