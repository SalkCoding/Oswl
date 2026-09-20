package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;

/** Structural lineage requirements shared by bundle verification and import. */
public final class BundleLineage {
    private BundleLineage() {}

    public static void validate(JsonNode meta) throws IOException {
        if (meta == null) return;
        JsonNode mode = meta.path("mode");
        if (mode.isMissingNode() && !meta.has("basedOnBundleId")) return;
        if (!mode.isTextual() || !(mode.asText().equals("full") || mode.asText().equals("delta")))
            throw new IOException("Invalid snapshot delta/full mode");
        if (mode.asText().equals("full")) {
            if (meta.has("basedOnBundleId")) throw new IOException("A full bundle cannot declare a delta base");
            return;
        }
        JsonNode version = meta.path("formatVersion");
        JsonNode id = meta.path("bundleId");
        JsonNode base = meta.path("basedOnBundleId");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 2
                || !meta.path("files").isObject() || !id.isTextual() || !base.isTextual()
                || !validId(id.asText()) || !validId(base.asText()) || id.asText().equals(base.asText()))
            throw new IOException("Snapshot delta requires versioned metadata and distinct valid bundle/base IDs; rebuild a full bundle");
    }

    static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 64 && id.equals(id.strip());
    }
}
