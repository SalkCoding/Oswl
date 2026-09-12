package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * CISA Known Exploited Vulnerabilities feed — same URL as the live
 * {@code com.salkcoding.oswl.client.KevCatalogService}. Always fetched in full (~1-2k entries);
 * not scoped to a wanted-list.
 */
final class KevSource {

    /** Mirrors {@code KevCatalogService.KEV_FEED_URL} — kept as a separate literal so this CLI
     * package has no runtime dependency on the Spring-managed client. */
    private static final String KEV_FEED_URL =
            "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";

    private final ObjectMapper mapper;

    KevSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Result(Set<String> cveIds, LocalDate asOf) {}

    Result fetch(HttpCache cache) throws Exception {
        byte[] body = cache.getOrFetch("kev.json", KEV_FEED_URL);
        JsonNode root = mapper.readTree(body);
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode v : root.path("vulnerabilities")) {
            String cveId = v.path("cveID").asText(null);
            if (cveId != null && !cveId.isBlank()) {
                ids.add(cveId.strip().toUpperCase(Locale.ROOT));
            }
        }
        JsonNode dateReleased = root.path("dateReleased");
        if (!dateReleased.isTextual() || dateReleased.asText().length() > 64) {
            throw new java.io.IOException("KEV requires a valid release timestamp");
        }
        java.time.Instant released;
        try {
            released = java.time.Instant.parse(dateReleased.asText());
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new java.io.IOException("KEV requires a valid release timestamp", invalid);
        }
        if (released.isAfter(java.time.Instant.now())) throw new java.io.IOException("KEV release timestamp is in the future");
        return new Result(ids, released.atOffset(java.time.ZoneOffset.UTC).toLocalDate());
    }
}
