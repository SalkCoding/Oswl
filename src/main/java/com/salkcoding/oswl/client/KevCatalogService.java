package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.RestClient;

import java.util.Collections;
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
    private volatile Set<String> kevCveIds = Set.of();
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
        this.airgapped = airgapped && snapshotService != null;
        if (this.airgapped) {
            log.info("[KEV] Air-gapped mode — KEV catalog served from the offline snapshot store, no outbound HTTP");
        }
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 86_400_000)
    public void refresh() {
        if (airgapped) {
            Set<String> ids = snapshotService.loadKevCveIds();
            kevCveIds = Collections.unmodifiableSet(ids);
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
            Set<String> ids = ConcurrentHashMap.newKeySet();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object cveId = map.get("cveID");
                    if (cveId != null && !cveId.toString().isBlank()) {
                        ids.add(cveId.toString().strip().toUpperCase());
                    }
                }
            }
            kevCveIds = Collections.unmodifiableSet(ids);
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
        if (cveId == null || !cveId.startsWith("CVE-")) return false;
        return kevCveIds.contains(cveId.strip().toUpperCase());
    }
}
