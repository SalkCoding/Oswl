package com.salkcoding.oswl.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry that tracks the current AI enrichment progress message
 * for each ScanResult (keyed by scanResultId).
 *
 * VulnerabilityEnrichmentService writes progress labels here, and QuickImportService
 * reads them to provide real-time status in the import log.
 */
@Component
public class EnrichmentProgressHolder {

    private final ConcurrentHashMap<Long, String> progress = new ConcurrentHashMap<>();

    /** Updates the current progress message for a scan. */
    public void set(Long scanResultId, String message) {
        progress.put(scanResultId, message);
    }

    /** Returns the latest progress message, or null if the scan is not being tracked. */
    public String get(Long scanResultId) {
        return progress.get(scanResultId);
    }

    /** Removes the entry when enrichment is complete. */
    public void remove(Long scanResultId) {
        progress.remove(scanResultId);
    }
}
