package com.salkcoding.oswl.dto.snapshot;

import java.util.List;

/** Findings and coverage captured from the same snapshot read. */
public record SnapshotLookup<T>(List<T> findings, boolean complete, List<T> withdrawnFindings) {
    public SnapshotLookup(List<T> findings, boolean complete) {
        this(findings, complete, List.of());
    }
    public SnapshotLookup {
        findings = List.copyOf(findings);
        withdrawnFindings = withdrawnFindings == null ? List.of() : List.copyOf(withdrawnFindings);
    }
}
