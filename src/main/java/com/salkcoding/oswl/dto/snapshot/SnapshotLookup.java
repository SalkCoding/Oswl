package com.salkcoding.oswl.dto.snapshot;

import java.util.List;

/** Findings and coverage captured from the same snapshot read. */
public record SnapshotLookup<T>(List<T> findings, boolean complete) {
    public SnapshotLookup {
        findings = List.copyOf(findings);
    }
}
