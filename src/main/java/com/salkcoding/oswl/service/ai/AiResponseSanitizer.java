package com.salkcoding.oswl.service.ai;

/**
 * Normalizes plain-text AI responses before storing or displaying them.
 */
public final class AiResponseSanitizer {

    private AiResponseSanitizer() {}

    public static String sanitizePlainText(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        String text = raw.strip();

        // Strip markdown code fences
        if (text.startsWith("```")) {
            int end = text.lastIndexOf("```");
            if (end > 3) {
                text = text.substring(3, end).strip();
                if (text.contains("\n")) {
                    text = text.substring(text.indexOf('\n') + 1).strip();
                }
            }
        }

        // If the model returned a JSON array with a single string/object, unwrap lightly
        if (text.startsWith("[\"")) {
            int close = text.indexOf("\"]");
            if (close > 2) {
                text = text.substring(2, close).strip();
            }
        } else if (text.startsWith("[{")) {
            int summaryKey = text.indexOf("\"summary\"");
            if (summaryKey >= 0) {
                int colon = text.indexOf(':', summaryKey);
                int openQuote = text.indexOf('"', colon + 1);
                int closeQuote = text.indexOf('"', openQuote + 1);
                if (openQuote >= 0 && closeQuote > openQuote) {
                    text = text.substring(openQuote + 1, closeQuote).strip();
                }
            }
        }

        return text.replace("\\n", "\n").strip();
    }
}
