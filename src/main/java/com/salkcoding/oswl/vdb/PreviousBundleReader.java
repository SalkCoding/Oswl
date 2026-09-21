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

    record PreviousBundle(String bundleId, Map<String, Map<String, String>> linesByFileAndKey, String wantedListId, String distributionProfile) {
        PreviousBundle(String bundleId, Map<String, Map<String, String>> linesByFileAndKey, String wantedListId) {
            this(bundleId, linesByFileAndKey, wantedListId, "unreviewed");
        }
    }

    static PreviousBundle read(Path bundlePath, ObjectMapper mapper) throws IOException {
        return read(bundlePath, mapper, true);
    }

    static void verify(Path bundlePath, ObjectMapper mapper) throws IOException {
        read(bundlePath, mapper, false);
    }

    private static PreviousBundle read(Path bundlePath, ObjectMapper mapper, boolean baseline) throws IOException {
        try {
            com.salkcoding.oswl.service.snapshot.SnapshotBundleStager.validateImportLimits(bundlePath,
                    java.util.Set.of("osv.jsonl", "depsdev.jsonl", "github-advisory.jsonl", "nvd.jsonl",
                            "epss.jsonl", "kev.jsonl", "unresolved.jsonl"));
        } catch (com.salkcoding.oswl.exception.InvalidRequestException e) {
            throw new IOException("Bundle exceeds import transport constraints: " + e.getMessage(), e);
        }

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
        var strictReader = mapper.reader()
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        String bundleId = null;
        String wantedListId = null;
        String distributionProfile = "unreviewed";
        boolean delta = false;
        if (!baseline && metaBytes == null) throw new IOException("Verification requires a files manifest");
        if (metaBytes != null) {
            JsonNode meta = strictReader.readTree(metaBytes);
            if (meta == null || !meta.isObject()) throw new IOException("Previous-bundle metadata must be an object");
            BundleLineage.validate(meta);
            if (meta.has("mode")) {
                JsonNode mode = meta.path("mode");
                if (!mode.isTextual() || !(mode.asText().equals("full") || (!baseline && mode.asText().equals("delta")))) {
                    throw new IOException("Unsupported bundle mode for this operation; delta generation requires a full baseline");
                }
            }
            if (!baseline && !meta.path("files").isObject()) throw new IOException("Verification requires a files manifest");
            if (meta.has("distributionProfile")) {
                var profile = meta.path("distributionProfile");
                if (!profile.isTextual() || !java.util.Set.of("unreviewed", "github-attributed").contains(profile.asText()))
                    throw new IOException("Unknown bundle distribution profile");
                distributionProfile = profile.asText();
            }
            verifyManifest(meta, files);
            delta = "delta".equals(meta.path("mode").asText());
            bundleId = meta.path("bundleId").asText(null);
            JsonNode wantedId = meta.path("wantedListId");
            if (wantedId.isTextual() && !wantedId.asText().isBlank()) wantedListId = wantedId.asText();
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (!java.util.Set.of("osv.jsonl", "github-advisory.jsonl", "unresolved.jsonl", "depsdev.jsonl", "epss.jsonl", "kev.jsonl")
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
        if (distributionProfile.equals("github-attributed")) verifyAttributedProfile(files.keySet(), result, mapper, delta);
        return new PreviousBundle(bundleId, result, wantedListId, distributionProfile);
    }

    private static void verifyAttributedProfile(java.util.Set<String> files, Map<String, Map<String, String>> rows,
                                                ObjectMapper mapper, boolean delta) throws IOException {
        var policy = new BundleDistributionPolicy("github-attributed", delta);
        policy.validateFiles(files);
        for (var file : rows.entrySet()) {
            for (String line : file.getValue().values()) policy.accept(file.getKey(), mapper.readTree(line));
        }
        policy.finish();
    }

    private static void verifyManifest(JsonNode meta, Map<String, byte[]> files) throws IOException {
        JsonNode version = meta.path("formatVersion");
        if (!version.isMissingNode() && (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() < 1 || version.intValue() > 4)) {
            throw new IOException("Unsupported previous-bundle formatVersion");
        }
        JsonNode manifest = meta.path("files");
        if (manifest.isMissingNode() && (version.isMissingNode() || version.intValue() == 1)) return;
        if (!manifest.isObject()) throw new IOException("Previous-bundle files manifest must be an object");
        var declared = new java.util.HashSet<String>();
        manifest.fieldNames().forEachRemaining(declared::add);
        if (!declared.equals(files.keySet())) throw new IOException("Previous-bundle manifest inventory mismatch");
        for (var entry : files.entrySet()) {
            JsonNode record = manifest.path(entry.getKey());
            JsonNode hash = record.path("sha256");
            if (!hash.isTextual() || !hash.asText().matches("[0-9a-fA-F]{64}")) {
                throw new IOException("Previous-bundle manifest requires SHA-256");
            }
            String actual;
            try {
                actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue()));
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required", impossible);
            }
            if (!actual.equalsIgnoreCase(hash.asText())) throw new IOException("Previous-bundle SHA-256 mismatch");
            JsonNode lines = record.path("lines");
            long actualLines = new String(entry.getValue(), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).count();
            if (!lines.isIntegralNumber() || !lines.canConvertToLong() || lines.longValue() < 0 || lines.longValue() != actualLines) {
                throw new IOException("Previous-bundle line count mismatch");
            }
        }
    }

    static String extractKey(String filename, JsonNode node) {
        return switch (filename) {
            case "osv.jsonl", "github-advisory.jsonl", "unresolved.jsonl" -> AirgappedSnapshotService.componentKey(
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
