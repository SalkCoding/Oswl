package com.salkcoding.oswl.service.ai;

/**
 * Thread-scoped project attribution for AI usage events. AI calls are recorded deep in the
 * HTTP client layer ({@link OpenAiClient} / {@link AnthropicClient}), far from the callers
 * that know which project triggered them. The enrichment / component-detail / version-diff
 * entry points open a {@link #scope(String)} around their AI calls so
 * {@link AiUsageRecorderService} can stamp each event with the originating project name.
 *
 * <p>Everything from the entry point down to the recorder runs on the same thread (the HTTP
 * call and the REQUIRES_NEW record transaction are synchronous), so a {@link ThreadLocal}
 * carries the value without threading it through every method signature. Scopes restore the
 * previous binding on close, so they nest safely and never leak on pooled request threads.
 */
public final class AiUsageContext {

    private static final ThreadLocal<String> PROJECT = new ThreadLocal<>();

    private AiUsageContext() {}

    /** Current project name for this thread, or {@code null} for non-project calls (e.g. connection tests). */
    public static String currentProject() {
        return PROJECT.get();
    }

    /**
     * Binds {@code projectName} to the current thread until the returned scope is closed,
     * restoring any previously bound value. Use in try-with-resources around AI calls.
     */
    public static Scope scope(String projectName) {
        String previous = PROJECT.get();
        PROJECT.set(projectName);
        return () -> {
            if (previous != null) {
                PROJECT.set(previous);
            } else {
                PROJECT.remove();
            }
        };
    }

    /** AutoCloseable that restores the previous project binding; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
