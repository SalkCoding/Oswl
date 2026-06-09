package com.salkcoding.oswl.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CISA Known Exploited Vulnerabilities (KEV) catalog.
 * Refreshed daily; used to flag actively exploited CVEs in AI prompts.
 */
@Slf4j
@Component
public class KevCatalogService {

    private static final String KEV_FEED_URL =
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    private final RestClient restClient = RestClient.create();
    private volatile Set<String> kevCveIds = Set.of();

    @Scheduled(initialDelay = 5_000, fixedDelay = 86_400_000)
    public void refresh() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = restClient.get()
                    .uri(KEV_FEED_URL)
                    .retrieve()
                    .body(Map.class);
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
            log.warn("[KEV] Failed to refresh catalog: {}", e.getMessage());
        }
    }

    public boolean isListed(String cveId) {
        if (cveId == null || !cveId.startsWith("CVE-")) return false;
        return kevCveIds.contains(cveId.strip().toUpperCase());
    }
}
