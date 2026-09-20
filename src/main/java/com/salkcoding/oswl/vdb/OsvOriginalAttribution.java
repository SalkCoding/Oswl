package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/** Identifies declared upstream attribution; this is not an authenticity check of cached data. */
final class OsvOriginalAttribution {
    private OsvOriginalAttribution() {}

    static String githubSource(JsonNode original) {
        if (original == null) return null;
        String id = original.path("id").asText("");
        if (!id.matches("GHSA-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}-[23456789cfghjmpqrvwx]{4}")) return null;
        JsonNode affected = original.path("affected");
        if (!affected.isArray() || affected.isEmpty()) return null;
        String expected = "https://github\\.com/github/advisory-database/blob/main/advisories/"
                + "(?:github-reviewed|unreviewed)/[0-9]{4}/(?:0[1-9]|1[0-2])/" + Pattern.quote(id) + "/" + Pattern.quote(id) + "\\.json";
        String source = null;
        for (JsonNode entry : affected) {
            JsonNode declared = entry.path("database_specific").path("source");
            if (!declared.isTextual() || !declared.asText().matches(expected)) return null;
            if (source != null && !source.equals(declared.asText())) return null;
            source = declared.asText();
        }
        return source;
    }
}
