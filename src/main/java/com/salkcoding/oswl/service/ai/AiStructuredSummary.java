package com.salkcoding.oswl.service.ai;

import java.util.Map;

/** Parses and formats structured AI batch responses for storage/display. */
public final class AiStructuredSummary {

    private AiStructuredSummary() {}

    public record ParsedEntry(String summary, String priority, String recommendedAction) {

        public static ParsedEntry fromMap(Map<String, String> entry) {
            if (entry == null) return null;
            String summary = blankToNull(entry.get("summary"));
            if (summary == null) return null;
            return new ParsedEntry(summary, blankToNull(entry.get("priority")),
                    blankToNull(entry.get("recommendedAction")));
        }

        public String formatForDisplay() {
            StringBuilder sb = new StringBuilder();
            if (priority != null) sb.append('[').append(priority).append("] ");
            sb.append(summary);
            if (recommendedAction != null) sb.append(" Action: ").append(recommendedAction);
            return sb.toString().trim();
        }
    }

    public static ParsedEntry parse(Map<String, String> entry) {
        return ParsedEntry.fromMap(entry);
    }

    public static String formatForDisplay(Map<String, String> entry) {
        ParsedEntry parsed = ParsedEntry.fromMap(entry);
        return parsed != null ? parsed.formatForDisplay() : null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }
}
