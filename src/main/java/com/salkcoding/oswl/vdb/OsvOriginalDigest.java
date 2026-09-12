package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Content identity for stored evidence, not source authentication. */
public final class OsvOriginalDigest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OsvOriginalDigest() {}

    public static String of(JsonNode original) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(ordered(original))));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot identify OSV original content", failure);
        }
    }

    private static Object ordered(JsonNode value) {
        if (value.isObject()) {
            Map<String, Object> ordered = new TreeMap<>();
            value.properties().forEach(entry -> ordered.put(entry.getKey(), ordered(entry.getValue())));
            return ordered;
        }
        if (value.isArray()) {
            List<Object> ordered = new ArrayList<>();
            value.forEach(element -> ordered.add(ordered(element)));
            return ordered;
        }
        return value;
    }
}
