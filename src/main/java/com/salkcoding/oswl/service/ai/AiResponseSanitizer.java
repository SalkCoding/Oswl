package com.salkcoding.oswl.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes plain-text AI responses before storing or displaying them.
 *
 * Smaller models (Gemini flash, local LLMs) often over-apply the "batch tasks
 * return JSON" system rule and wrap even single-sentence answers in JSON arrays
 * or objects, or markdown fences. This unwraps any such structure back to the
 * human-readable text instead of leaking raw {@code ["...", ...]} to the UI.
 *
 * Thinking models (e.g. Qwen3 behind an OpenAI-compatible endpoint) may also
 * inline their reasoning as a {@code <think>...</think>} block in the content —
 * or return only the reasoning tail plus {@code </think>} when the chat template
 * pre-fills the opening tag. Reasoning is never user-facing, so it is stripped.
 */
public final class AiResponseSanitizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Complete {@code <think>...</think>} sections (reasoning may span lines). */
    private static final Pattern THINK_SECTION =
            Pattern.compile("(?is)<think\\s*>.*?</think\\s*>");

    /** Object fields that carry the human-readable payload, in preference order. */
    private static final List<String> TEXT_FIELDS =
            List.of("summary", "insight", "text", "content", "message", "answer");

    private AiResponseSanitizer() {}

    public static String sanitizePlainText(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String text = stripCodeFence(stripReasoning(raw.strip()));

        if (text.startsWith("[") || text.startsWith("{") || text.startsWith("\"")) {
            String extracted = tryExtractFromJson(text);
            if (extracted != null && !extracted.isBlank()) {
                text = extracted;
            }
        }

        return text.replace("\\n", "\n").strip();
    }

    /**
     * Removes inlined reasoning from thinking models. Handles a complete
     * {@code <think>} section, a stray closing tag whose opener was pre-filled by
     * the chat template (everything before it is reasoning), and an opener left
     * unclosed when max_tokens truncates the response (nothing usable after it).
     */
    private static String stripReasoning(String text) {
        String out = THINK_SECTION.matcher(text).replaceAll("");
        String lower = out.toLowerCase(Locale.ROOT);
        int open = lower.indexOf("<think>");
        int close = lower.indexOf("</think>");
        if (close >= 0 && (open < 0 || close < open)) {
            out = out.substring(close + "</think>".length());
            lower = out.toLowerCase(Locale.ROOT);
            open = lower.indexOf("<think>");
        }
        if (open >= 0) {
            out = out.substring(0, open);
        }
        return out.strip();
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
            if (!sb.isEmpty()) return sb.toString();
            // Unknown envelope — the model invented its own keys (e.g.
            // {"trend": "...", "new_risk": "...", "priority_action": "..."}).
            // Flatten the textual values rather than leak raw JSON to the UI.
            List<String> parts = new ArrayList<>();
            collectText(node, parts);
            return parts.isEmpty() ? null : String.join(" ", parts);
        }
        return null;
    }

    /** Collects textual leaf values in field order; numbers/booleans are skipped as noise. */
    private static void collectText(JsonNode node, List<String> out) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            if (!node.asText().isBlank()) out.add(node.asText().strip());
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectText(e.getValue(), out));
        }
    }
}
