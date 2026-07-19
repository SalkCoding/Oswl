package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes plain-text AI responses before storing or displaying them.
 *
 * Smaller models (Gemini flash, local LLMs) often over-apply the "batch tasks
 * return JSON" system rule and wrap even single-sentence answers in JSON arrays
 * or objects, or markdown fences. This unwraps any such structure back to the
 * human-readable text instead of leaking raw {@code ["...", ...]} to the UI.
 */
public final class AiResponseSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Object fields that carry the human-readable payload, in preference order. */
    private static final List<String> TEXT_FIELDS =
            List.of("summary", "insight", "text", "content", "message", "answer");

    private AiResponseSanitizer() {}

    public static String sanitizePlainText(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String text = stripCodeFence(raw.strip());

        if (text.startsWith("[") || text.startsWith("{") || text.startsWith("\"")) {
            String extracted = tryExtractFromJson(text);
            if (extracted != null && !extracted.isBlank()) {
                text = extracted;
            }
        }

        return text.replace("\\n", "\n").strip();
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) return text;
        int end = text.lastIndexOf("```");
        if (end <= 3) return text;
        String inner = text.substring(3, end).strip();
        // Drop the language tag line (```json etc.)
        int firstNewline = inner.indexOf('\n');
        if (firstNewline >= 0 && !inner.substring(0, firstNewline).contains(" ")
                && inner.substring(0, firstNewline).length() <= 12) {
            inner = inner.substring(firstNewline + 1);
        }
        return inner.strip();
    }

    private static String tryExtractFromJson(String text) {
        try {
            return joinTextNodes(MAPPER.readTree(text));
        } catch (Exception e) {
            // Model mixed prose with a trailing/leading JSON blob — try the bracketed part alone
            int start = text.indexOf('[');
            int end = text.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    return joinTextNodes(MAPPER.readTree(text.substring(start, end + 1)));
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return null;
        }
    }

    private static String joinTextNodes(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isValueNode()) return node.asText();
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode child : node) {
                String s = joinTextNodes(child);
                if (s != null && !s.isBlank()) parts.add(s.strip());
            }
            return parts.isEmpty() ? null : String.join("\n", parts);
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            for (String field : TEXT_FIELDS) {
                JsonNode v = node.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    sb.append(v.asText().strip());
                    break;
                }
            }
            JsonNode action = node.get("recommendedAction");
            if (action != null && action.isTextual() && !action.asText().isBlank()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(action.asText().strip());
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return null;
    }
}
