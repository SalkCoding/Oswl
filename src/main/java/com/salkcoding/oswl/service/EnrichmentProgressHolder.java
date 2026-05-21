package com.salkcoding.oswl.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry that tracks the current AI enrichment progress message
 * for each ScanResult (keyed by scanResultId).
 *
 * VulnerabilityEnrichmentService writes progress labels here; QuickImportService
 * reads them to provide live status updates in the import log.
 */
@Component
public class EnrichmentProgressHolder {

    private final ConcurrentHashMap<Long, String> progress = new ConcurrentHashMap<>();

    /** Update the current progress message for a scan. */
    public void set(Long scanResultId, String message) {
        progress.put(scanResultId, message);
    }

    /** Retrieve the latest progress message, or null if not tracked. */
    public String get(Long scanResultId) {
        return progress.get(scanResultId);
    }

    /** Remove the entry once enrichment is complete. */
    public void remove(Long scanResultId) {
        progress.remove(scanResultId);
    }
}
