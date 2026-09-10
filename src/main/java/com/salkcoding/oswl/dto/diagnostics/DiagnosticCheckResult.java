package com.salkcoding.oswl.dto.diagnostics;

/**
 * One self-diagnostics check result. {@code detail} is always safe to display or
 * copy into a support request — no secrets, tokens, or passwords are ever placed there.
 */
public record DiagnosticCheckResult(
        String id,
        String label,
        String status,   // UP | DOWN | SKIPPED | UNKNOWN
        String detail,
        long tookMs
) {
}
