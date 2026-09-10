package com.salkcoding.oswl.dto.scan;

/** Outcome of one archiving run for a single project — how many scans were archived just now. */
public record ScanArchiveResult(Long projectId, int retainCount, int totalCompletedScans, int archivedNow) {
}
