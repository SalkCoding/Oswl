package com.salkcoding.oswl.service;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Thread-scoped channel for live enrichment progress (D2 AI streaming previews, D3 continuous
 * progress). The entry points that know the scan ({@link VulnerabilityEnrichmentService}) open a
 * {@link #scope(Frame)} around their work; the layers that produce progress signals
 * ({@code OpenAiClient} token stream, {@code DepsDevClient} fetch completions,
 * {@code AiAnalysisService} batch chunks) read the current frame without the scan id being
 * threaded through every method signature — the same reason {@code AiUsageContext} exists.
 *
 * <p>Everything runs synchronously on the thread that opened the scope (the HTTP calls are
 * blocking), so a {@link ThreadLocal} is sufficient; scopes restore the previous binding on
 * close and therefore nest safely and never leak on pooled/virtual threads.
 */
public final class EnrichmentProgressContext {

    /**
     * @param scanResultId  scan this progress belongs to
     * @param previewSink   receives raw AI text deltas for the live Quick Import preview (D2)
     * @param fetchProgress receives the running count of completed deps.dev fetches (D3 data phase)
     * @param batchProgress receives the running count of processed AI batch items (D2 detail lines)
     */
    public record Frame(Long scanResultId,
                        Consumer<String> previewSink,
                        IntConsumer fetchProgress,
                        IntConsumer batchProgress) {}

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private EnrichmentProgressContext() {}

    /** Current frame for this thread, or {@code null} outside an enrichment scope. */
    public static Frame current() {
        return CURRENT.get();
    }

    public static Consumer<String> currentPreviewSink() {
        Frame frame = CURRENT.get();
        return frame != null ? frame.previewSink() : null;
    }

    public static IntConsumer currentFetchProgress() {
        Frame frame = CURRENT.get();
        return frame != null ? frame.fetchProgress() : null;
    }

    public static IntConsumer currentBatchProgress() {
        Frame frame = CURRENT.get();
        return frame != null ? frame.batchProgress() : null;
    }

    /**
     * Binds {@code frame} to the current thread until the returned scope is closed, restoring
     * any previously bound frame. Use in try-with-resources around enrichment work.
     */
    public static Scope scope(Frame frame) {
        Frame previous = CURRENT.get();
        CURRENT.set(frame);
        return () -> {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        };
    }

    /** AutoCloseable that restores the previous frame binding; never throws. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
