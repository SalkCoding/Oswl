package com.salkcoding.oswl.dto;

/**
 * Outcome of an AI provider connection test ({@code POST /api/settings/ai/test-connection}).
 *
 * @param success whether the probe received a valid model response
 * @param message short explanation of the result or failure (localized)
 * @param hint    optional next step for the user when {@code success} is false
 */
public record AiConnectionTestResult(boolean success, String message, String hint) {

    public static AiConnectionTestResult ok(String message) {
        return new AiConnectionTestResult(true, message, null);
    }

    /** Reachable and authenticated, but something is worth flagging (e.g. unknown model id). */
    public static AiConnectionTestResult okWithWarning(String message, String hint) {
        return new AiConnectionTestResult(true, message, hint);
    }

    public static AiConnectionTestResult fail(String message, String hint) {
        return new AiConnectionTestResult(false, message, hint);
    }
}
