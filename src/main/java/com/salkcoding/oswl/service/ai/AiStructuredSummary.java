package com.salkcoding.oswl.service.ai;

import java.util.Map;

/** Parses and formats structured AI batch responses for storage/display. */
public final class AiStructuredSummary {

    private AiStructuredSummary() {}

    public static String formatForDisplay(Map<String, String> entry) {
        if (entry == null) return null;
        String summary = blankToNull(entry.get("summary"));
        if (summary == null) return null;

        String priority = blankToNull(entry.get("priority"));
        String action   = blankToNull(entry.get("recommendedAction"));

        StringBuilder sb = new StringBuilder();
        if (priority != null) sb.append('[').append(priority).append("] ");
        sb.append(summary);
        if (action != null) sb.append(" Action: ").append(action);
        return sb.toString().trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
