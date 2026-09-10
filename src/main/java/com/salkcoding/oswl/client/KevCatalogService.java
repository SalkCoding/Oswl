package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CISA Known Exploited Vulnerabilities (KEV) catalog.
 * Refreshed daily; used to flag actively exploited CVEs in AI prompts.
 *
 * Air-gapped mode: when constructed with a snapshot store and the air-gapped flag,
 * the scheduled refresh loads the catalog from the offline snapshot store instead
 * of the CISA feed — no outbound HTTP is attempted.
 */
@Slf4j
public class KevCatalogService {

    private static final String KEV_FEED_URL =
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    private final RestClient restClient = RestClient.create();
    private final AirgappedSnapshotService snapshotService;
    private final boolean airgapped;
    record CatalogState(Set<String> ids, java.time.Instant loadedAt) { }
    private volatile CatalogState catalog = new CatalogState(Set.of(), null);
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    /** Called once by Spring config after construction to enable external-API metrics. */
    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

    /** Live-HTTP catalog (no snapshot store). Used directly by unit tests. */
    public KevCatalogService() {
        this(null, false);
    }

    public KevCatalogService(AirgappedSnapshotService snapshotService, boolean airgapped) {
        this.snapshotService = snapshotService;
        if (airgapped && snapshotService == null)
            throw new IllegalArgumentException("Air-gapped mode requires a snapshot service");
        this.airgapped = airgapped;
        if (this.airgapped) {
            log.info("[KEV] Air-gapped mode — KEV catalog served from the offline snapshot store, no outbound HTTP");
        }
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 86_400_000)
    public void refresh() {
        catalog = new CatalogState(catalog.ids(), null);
        if (airgapped) {
            Set<String> ids = snapshotService.loadKevCveIds();
            catalog = new CatalogState(Set.copyOf(ids), java.time.Instant.now());
            log.info("[KEV] Air-gapped mode — loaded {} known exploited CVE entries from the offline snapshot", ids.size());
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(KEV_FEED_URL)
                    .retrieve()
                    .body(Map.class);
            recordApiCall(OswlMetrics.OUTCOME_SUCCESS);
            if (body == null) return;
            Object vulns = body.get("vulnerabilities");
            if (!(vulns instanceof List<?> list)) return;
            Object count = body.get("count");
            if (!(count instanceof Integer || count instanceof Long)
                    || ((Number) count).longValue() != list.size()) return;
            if (!(body.get("catalogVersion") instanceof String version) || version.isBlank()) return;
            if (!(body.get("dateReleased") instanceof String date) || date.length() > 64) return;
            java.time.Instant released = java.time.Instant.parse(date);
            if (released.isAfter(java.time.Instant.now())) return;
            Set<String> ids = ConcurrentHashMap.newKeySet();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || !(map.get("cveID") instanceof String cveId)
                        || !cveId.matches("CVE-[0-9]{4}-[0-9]{4,19}")) return;
                if (!ids.add(cveId)) return;
            }
            catalog = new CatalogState(Set.copyOf(ids), java.time.Instant.now());
            log.info("[KEV] Loaded {} known exploited CVE entries", ids.size());
        } catch (Exception e) {
            recordApiCall(OswlMetrics.OUTCOME_FAILURE);
            log.warn("[KEV] Failed to refresh catalog: {}", e.getMessage());
        }
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("kev", outcome);
        }
    }

    public boolean isListed(String cveId) {
        return Boolean.TRUE.equals(listingStatus(cveId));
    }

    /** Null means current absence cannot be established; retained positive evidence stays listed. */
    public Boolean listingStatus(String cveId) {
        if (cveId == null) return null;
        String id = cveId.strip().toUpperCase(java.util.Locale.ROOT);
        if (!id.matches("CVE-[0-9]{4}-[0-9]{4,}")) return null;
        CatalogState state = catalog;
        if (state.ids().contains(id)) return true;
        java.time.Instant loaded = state.loadedAt();
        if (loaded == null || loaded.isAfter(java.time.Instant.now())
                || loaded.plus(java.time.Duration.ofDays(1)).isBefore(java.time.Instant.now())) return null;
        if (airgapped && snapshotService.isSourceStaleOrUndated(AirgappedSnapshotService.SOURCE_KEV)) return null;
        return false;
    }
}
