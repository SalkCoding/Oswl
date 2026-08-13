package com.salkcoding.oswl.service.cvss;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parsed CVSS v4.0 metrics from a vector string
 * (e.g. {@code CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N}).
 *
 * <p>Holds the 11 required Base metrics plus whichever optional Threat (E) / Environmental
 * (CR/IR/AR/M*) metrics the vector supplies — CVSS v4.0's Base Score is simply what this same
 * scoring algorithm computes when those optional metrics are absent/"X" (see
 * {@link CvssV4Calculator}), so one parser and one calculator path cover both. Supplemental
 * metrics (S/AU/R/V/RE/U) are accepted for a valid parse but never affect scoring — CVSS itself
 * defines them as informational only.
 */
final class CvssV4Vector {

    private static final Set<String> REQUIRED_BASE_METRICS =
            Set.of("AV", "AC", "AT", "PR", "UI", "VC", "VI", "VA", "SC", "SI", "SA");

    /** Metric code → allowed values (order irrelevant here, just membership). First value listed is never special. */
    private static final Map<String, Set<String>> ALLOWED_VALUES = Map.ofEntries(
            Map.entry("AV", Set.of("N", "A", "L", "P")),
            Map.entry("AC", Set.of("L", "H")),
            Map.entry("AT", Set.of("N", "P")),
            Map.entry("PR", Set.of("N", "L", "H")),
            Map.entry("UI", Set.of("N", "P", "A")),
            Map.entry("VC", Set.of("H", "L", "N")),
            Map.entry("VI", Set.of("H", "L", "N")),
            Map.entry("VA", Set.of("H", "L", "N")),
            Map.entry("SC", Set.of("H", "L", "N")),
            Map.entry("SI", Set.of("H", "L", "N")),
            Map.entry("SA", Set.of("H", "L", "N")),
            Map.entry("E", Set.of("X", "A", "P", "U")),
            Map.entry("CR", Set.of("X", "H", "M", "L")),
            Map.entry("IR", Set.of("X", "H", "M", "L")),
            Map.entry("AR", Set.of("X", "H", "M", "L")),
            Map.entry("MAV", Set.of("X", "N", "A", "L", "P")),
            Map.entry("MAC", Set.of("X", "L", "H")),
            Map.entry("MAT", Set.of("X", "N", "P")),
            Map.entry("MPR", Set.of("X", "N", "L", "H")),
            Map.entry("MUI", Set.of("X", "N", "P", "A")),
            Map.entry("MVC", Set.of("X", "H", "L", "N")),
            Map.entry("MVI", Set.of("X", "H", "L", "N")),
            Map.entry("MVA", Set.of("X", "H", "L", "N")),
            Map.entry("MSC", Set.of("X", "H", "L", "N")),
            Map.entry("MSI", Set.of("X", "S", "H", "L", "N")),
            Map.entry("MSA", Set.of("X", "S", "H", "L", "N")),
            Map.entry("S", Set.of("X", "N", "P")),
            Map.entry("AU", Set.of("X", "N", "Y")),
            Map.entry("R", Set.of("X", "A", "U", "I")),
            Map.entry("V", Set.of("X", "D", "C")),
            Map.entry("RE", Set.of("X", "L", "M", "H")),
            Map.entry("U", Set.of("X", "Clear", "Green", "Amber", "Red")));

    private final Map<String, String> metrics;

    private CvssV4Vector(Map<String, String> metrics) {
        this.metrics = metrics;
    }

    /**
     * @param vector a full vector string, with or without the {@code CVSS:4.0/} prefix
     * @return the parsed metrics, or {@code null} if the vector is not a well-formed CVSS v4.0
     *         vector (missing/unknown required metric, unrecognized metric code, or invalid value)
     */
    static CvssV4Vector parse(String vector) {
        if (vector == null || vector.isBlank()) {
            return null;
        }
        String trimmed = vector.strip();
        if (!trimmed.startsWith("CVSS:4.0/")) {
            return null;
        }
        String body = trimmed.substring(trimmed.indexOf('/') + 1);
        Map<String, String> parsed = new HashMap<>();
        for (String part : body.split("/")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) {
                return null;
            }
            String metric = kv[0];
            String value = kv[1];
            Set<String> allowed = ALLOWED_VALUES.get(metric);
            if (allowed == null || !allowed.contains(value)) {
                return null;
            }
            parsed.put(metric, value);
        }
        if (!parsed.keySet().containsAll(REQUIRED_BASE_METRICS)) {
            return null;
        }
        return new CvssV4Vector(parsed);
    }

    /** Raw metric value as given in the vector, or {@code null} if absent (never defaulted here). */
    String raw(String metric) {
        return metrics.get(metric);
    }

    CvssV4Vector withOverrides(Map<String, String> overrides) {
        Map<String, String> merged = new HashMap<>(metrics);
        merged.putAll(overrides);
        return new CvssV4Vector(merged);
    }
}
