package com.salkcoding.oswl.service.snapshot;

import java.time.LocalDate;
import java.util.Map;

/** Bounds a synchronous offline lookup sequence to one immutable generation. */
public final class SnapshotGenerationScope implements AutoCloseable {
    private static final ThreadLocal<SnapshotGenerationScope> CURRENT = new ThreadLocal<>();
    private final SnapshotGenerationScope previous;
    private final Thread owner = Thread.currentThread();
    private final long generationId;
    private final Map<String, LocalDate> sourceDates;

    SnapshotGenerationScope(long generationId, Map<String, LocalDate> sourceDates) {
        this.generationId = generationId;
        this.sourceDates = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(sourceDates));
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static SnapshotGenerationScope current() { return CURRENT.get(); }
    public long generationId() { return generationId; }
    public LocalDate sourceDate(String source) { return sourceDates.get(source); }

    @Override public void close() {
        if (Thread.currentThread() != owner || CURRENT.get() != this) throw new IllegalStateException("Snapshot scope closed out of order");
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
