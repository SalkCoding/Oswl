package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * E5 delta mode ({@code --since <previous-bundle.zip>}): reads a previously built bundle so the
 * new build can be diffed against it — same key-extraction rules the app's own
 * {@code AirgappedSnapshotService} ingest methods use, kept in sync manually since this is a
 * plain-Java CLI class outside the service's package.
 */
final class PreviousBundleReader {

    record PreviousBundle(String bundleId, Map<String, Map<String, String>> linesByFileAndKey) {}

    static PreviousBundle read(Path bundlePath, ObjectMapper mapper) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] metaBytes = null;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(bundlePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] content = zis.readAllBytes();
                if ("meta.json".equals(entry.getName())) metaBytes = content;
                else files.put(entry.getName(), content);
            }
        }
        String bundleId = null;
        if (metaBytes != null) {
            bundleId = mapper.readTree(metaBytes).path("bundleId").asText(null);
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Map<String, String> keyed = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(e.getValue()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node;
                    try {
                        node = mapper.readTree(line);
                    } catch (Exception ex) {
                        continue; // malformed line in the previous bundle — skip, don't fail the whole diff
                    }
                    String key = extractKey(e.getKey(), node);
                    if (key != null) keyed.put(key, line);
                }
            }
            result.put(e.getKey(), keyed);
        }
        return new PreviousBundle(bundleId, result);
    }

    static String extractKey(String filename, JsonNode node) {
        return switch (filename) {
            case "osv.jsonl", "unresolved.jsonl" -> AirgappedSnapshotService.componentKey(
                    text(node, "ecosystem"), text(node, "name"), text(node, "version"));
            case "depsdev.jsonl" -> "advisory".equals(text(node, "type"))
                    ? text(node, "ghsaId")
                    : AirgappedSnapshotService.componentKey(text(node, "ecosystem"), text(node, "name"), text(node, "version"));
            case "epss.jsonl", "kev.jsonl" -> {
                String c = text(node, "cveId");
                yield c == null ? null : c.strip().toUpperCase(Locale.ROOT);
            }
            default -> null;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
