package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashSet;
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
        if (root == null || !root.isObject() || !root.path("vulnerabilities").isArray()) {
            throw new java.io.IOException("KEV requires a vulnerability array");
        }
        JsonNode count = root.path("count");
        if (!count.isIntegralNumber() || !count.canConvertToLong()
                || count.longValue() != root.path("vulnerabilities").size()) {
            throw new java.io.IOException("KEV declared count does not match the catalog");
        }
        if (!root.path("catalogVersion").isTextual() || root.path("catalogVersion").asText().isBlank()) {
            throw new java.io.IOException("KEV requires a catalog version");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode v : root.path("vulnerabilities")) {
            JsonNode cveId = v.path("cveID");
            if (!cveId.isTextual() || !cveId.asText().matches("CVE-[0-9]{4}-[0-9]{4,19}") || !ids.add(cveId.asText())) {
                throw new java.io.IOException("KEV contains an invalid or duplicate CVE identity");
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
