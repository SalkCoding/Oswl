package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;

/**
 * Delta mode ({@code --since <previous-bundle.zip>}): reads a previously built bundle so the
 * new build can be diffed against it — same key-extraction rules the app's own
 * {@code AirgappedSnapshotService} ingest methods use, kept in sync manually since this is a
 * plain-Java CLI class outside the service's package.
 */
final class PreviousBundleReader {

    record PreviousBundle(String bundleId, Map<String, Map<String, String>> linesByFileAndKey) {}

    static PreviousBundle read(Path bundlePath, ObjectMapper mapper) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] metaBytes = null;
        try (ZipFile zip = new ZipFile(bundlePath.toFile())) {
            var entries = zip.entries();
            var names = new java.util.HashSet<String>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new IOException("Duplicate previous-bundle ZIP entry");
                if (entry.isDirectory()) continue;
                byte[] content;
                try (var input = zip.getInputStream(entry)) {
                    content = input.readAllBytes();
                }
                CRC32 crc = new CRC32();
                crc.update(content);
                if (content.length != entry.getSize() || crc.getValue() != entry.getCrc()) {
                    throw new IOException("Previous-bundle ZIP entry integrity mismatch");
                }
                if ("meta.json".equals(entry.getName())) metaBytes = content;
                else files.put(entry.getName(), content);
            }
        }
        String bundleId = null;
        if (metaBytes != null) {
            bundleId = mapper.readTree(metaBytes).path("bundleId").asText(null);
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        var strictReader = mapper.reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!java.util.Set.of("osv.jsonl", "unresolved.jsonl", "depsdev.jsonl", "epss.jsonl", "kev.jsonl")
                    .contains(e.getKey())) continue;
            Map<String, String> keyed = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new ByteArrayInputStream(e.getValue()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node;
                    try {
                        node = strictReader.readTree(line);
                    } catch (IOException ex) {
                        throw new IOException("Invalid previous-bundle JSON row in " + e.getKey(), ex);
                    }
                    if (node == null || !node.isObject()) {
                        throw new IOException("Previous-bundle row must be an object in " + e.getKey());
                    }
                    String key = extractKey(e.getKey(), node);
                    if (key == null || key.isBlank()) {
                        throw new IOException("Missing or invalid previous-bundle identity in " + e.getKey());
                    }
                    if (keyed.putIfAbsent(key, line) != null) {
                        throw new IOException("Duplicate previous-bundle identity in " + e.getKey());
                    }
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
        return v == null || !v.isTextual() || v.asText().isBlank() ? null : v.asText();
    }
}
