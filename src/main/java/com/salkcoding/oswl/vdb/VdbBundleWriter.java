package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an {@code oswl-vdb} v2 bundle (E1 format) byte-for-byte compatible with what
 * {@code AirgappedSnapshotService.importBundle()} expects — see that class's Javadoc for the
 * exact JSONL line schemas this mirrors.
 *
 * <p>E5 delta mode: when {@code previous} is given, each file's content is diffed key-by-key
 * against the previous bundle (see {@link PreviousBundleReader}) — only added/changed lines are
 * kept, and keys present in the previous bundle but absent from this build get a
 * {@code "_deleted":true} marker (E2's existing delete-marker convention), instead of writing
 * every source in full every time.
 */
final class VdbBundleWriter {

    private static final String BUNDLE_FORMAT = "oswl-vdb";
    private static final int FORMAT_VERSION = 2;
    static final String BUILDER_VERSION = "oswl-vdb/1.0.0";

    private final ObjectMapper mapper;

    VdbBundleWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** E6: recorded in {@code meta.json} only when the build was scoped by {@code --wanted} — a
     * v1-ignorant reader (or the app's own lenient JsonNode-based meta.json parser) simply never
     * sees these fields when absent, so this is not a schema break. */
    record WantedListInfo(String wantedListId, int wantedCount, int resolvedCount) {}

    void write(Path out,
               Map<String, List<SnapshotVuln>> osvByComponentKey, LocalDate osvAsOf,
               List<DepsDevSource.VersionRecord> depsdevVersions, List<DepsDevSource.AdvisoryRecord> depsdevAdvisories,
               Map<String, Integer> depsdevSkippedUnsupportedSystems,
               Map<String, Double> epssScores, LocalDate epssAsOf,
               java.util.Set<String> kevCveIds, LocalDate kevAsOf,
               int unresolvedComponentCount, WantedListInfo wantedListInfo,
               List<WantedComponent> unresolvedComponents,
               PreviousBundleReader.PreviousBundle previous) throws IOException {

        Map<String, String> unresolvedByKey = new LinkedHashMap<>();
        for (WantedComponent w : unresolvedComponents) {
            ObjectNode node = mapper.createObjectNode();
            node.put("ecosystem", w.ecosystem());
            node.put("name", w.name());
            node.put("version", w.version());
            String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(w.ecosystem(), w.name(), w.version());
            if (key != null) unresolvedByKey.put(key, writeJson(node));
        }

        Map<String, String> osvByKey = new LinkedHashMap<>();
        for (Map.Entry<String, List<SnapshotVuln>> e : osvByComponentKey.entrySet()) {
            String[] parts = e.getKey().split("\\|", 3);
            if (parts.length != 3) continue;
            ObjectNode node = mapper.createObjectNode();
            node.put("ecosystem", parts[0]);
            node.put("name", parts[1]);
            node.put("version", parts[2]);
            node.putArray("vulns").addAll(e.getValue().stream().map(v -> (JsonNode) mapper.valueToTree(v)).toList());
            osvByKey.put(e.getKey(), writeJson(node));
        }

        Map<String, String> depsdevByKey = new LinkedHashMap<>();
        for (DepsDevSource.VersionRecord v : depsdevVersions) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "version");
            node.put("ecosystem", v.ecosystem());
            node.put("name", v.name());
            node.put("version", v.version());
            node.putArray("licenses").addAll(v.licenses().stream().map(s -> (JsonNode) mapper.valueToTree(s)).toList());
            node.putArray("advisoryKeys").addAll(v.advisoryKeys().stream().map(s -> (JsonNode) mapper.valueToTree(s)).toList());
            node.put("isDefault", v.isDefault());
            if (v.deprecated() != null) node.put("deprecated", v.deprecated());
            String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(v.ecosystem(), v.name(), v.version());
            if (key != null) depsdevByKey.put(key, writeJson(node));
        }
        for (DepsDevSource.AdvisoryRecord a : depsdevAdvisories) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "advisory");
            node.put("ghsaId", a.ghsaId());
            if (a.title() != null) node.put("title", a.title());
            node.putArray("aliases").addAll(a.aliases().stream().map(s -> (JsonNode) mapper.valueToTree(s)).toList());
            if (a.cvss3Score() != null) node.put("cvss3Score", a.cvss3Score());
            if (a.cvss3Vector() != null) node.put("cvss3Vector", a.cvss3Vector());
            depsdevByKey.put(a.ghsaId(), writeJson(node));
        }

        Map<String, String> epssByKey = new LinkedHashMap<>();
        epssScores.forEach((cveId, score) -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("cveId", cveId);
            node.put("score", score);
            epssByKey.put(cveId, writeJson(node));
        });

        Map<String, String> kevByKey = new LinkedHashMap<>();
        for (String cveId : kevCveIds) {
            kevByKey.put(cveId, "{\"cveId\":\"" + cveId + "\"}");
        }

        boolean delta = previous != null;
        String osvContent = renderContent("osv.jsonl", osvByKey, previous);
        String depsdevContent = renderContent("depsdev.jsonl", depsdevByKey, previous);
        String epssContent = renderContent("epss.jsonl", epssByKey, previous);
        String kevContent = renderContent("kev.jsonl", kevByKey, previous);
        String unresolvedContent = renderContent("unresolved.jsonl", unresolvedByKey, previous);

        String bundleId = UUID.randomUUID().toString();
        LocalDateTime builtAt = LocalDateTime.now();

        ObjectNode meta = mapper.createObjectNode();
        meta.put("format", BUNDLE_FORMAT);
        meta.put("formatVersion", FORMAT_VERSION);
        meta.put("bundleId", bundleId);
        meta.put("mode", delta ? "delta" : "full");
        if (delta && previous.bundleId() != null) {
            meta.put("basedOnBundleId", previous.bundleId());
        }
        meta.put("builtAt", builtAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("builder", BUILDER_VERSION);
        if (wantedListInfo != null) {
            meta.put("wantedListId", wantedListInfo.wantedListId());
            meta.put("wantedCount", wantedListInfo.wantedCount());
            meta.put("resolvedCount", wantedListInfo.resolvedCount());
        }
        ObjectNode sources = meta.putObject("sources");
        putSourceMeta(sources, "osv", osvByKey.size(), osvAsOf, "osv.dev bulk dump");
        putSourceMeta(sources, "depsdev-version", depsdevVersions.size(), LocalDate.now(), "deps.dev api (wanted-list)");
        if (!depsdevSkippedUnsupportedSystems.isEmpty()) {
            // deps.dev covers only 7 systems (GO RUBYGEMS NPM CARGO MAVEN PYPI NUGET) — wanted
            // components on any other system (e.g. COMPOSER, CONAN) were never queried, so their
            // absence from depsdev.jsonl means "no data", not "no license/advisories".
            ObjectNode skipped = ((ObjectNode) sources.get("depsdev-version"))
                    .putObject("skippedUnsupportedSystems");
            depsdevSkippedUnsupportedSystems.forEach(skipped::put);
            skipped.put("_reason", "deps.dev does not support this system — component not queried");
        }
        putSourceMeta(sources, "depsdev-advisory", depsdevAdvisories.size(), LocalDate.now(), "deps.dev api");
        putSourceMeta(sources, "epss", epssByKey.size(), epssAsOf, "epss current");
        putSourceMeta(sources, "kev", kevByKey.size(), kevAsOf, "cisa kev");
        if (!unresolvedByKey.isEmpty()) {
            // E6: a distinct source (not folded into osv.jsonl) so the app's existing per-source
            // status/import-result plumbing (E1/E2/E3) surfaces it automatically — no bespoke
            // "no data" wiring needed on the import side, just a label on the admin UI (see
            // AirgappedSnapshotService.SOURCE_UNRESOLVED / SnapshotAdminController).
            putSourceMeta(sources, "unresolved", unresolvedByKey.size(), LocalDate.now(),
                    "components this build's wanted-list included but OSV/deps.dev never resolved");
        }
        if (unresolvedComponentCount > 0) {
            ObjectNode coverage = meta.putObject("coverage");
            coverage.put("unresolvedComponents", unresolvedComponentCount);
            coverage.put("note", "These wanted components had at least one OSV range-typed "
                    + "affected[] entry this builder could not confidently evaluate (non-SEMVER "
                    + "range type, or an unparseable version string) — they are NOT necessarily "
                    + "vulnerability-free, just unresolved. See E5.3 in PERFORMANCE-AND-OFFLINE-PLAN.md.");
        }
        ObjectNode files = meta.putObject("files");
        putFileMeta(files, "osv.jsonl", osvContent, countLines(osvContent));
        putFileMeta(files, "depsdev.jsonl", depsdevContent, countLines(depsdevContent));
        putFileMeta(files, "epss.jsonl", epssContent, countLines(epssContent));
        putFileMeta(files, "kev.jsonl", kevContent, countLines(kevContent));
        if (!unresolvedContent.isEmpty()) {
            putFileMeta(files, "unresolved.jsonl", unresolvedContent, countLines(unresolvedContent));
        }

        Files.createDirectories(out.toAbsolutePath().getParent() != null ? out.toAbsolutePath().getParent() : out.toAbsolutePath());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "meta.json", writeJson(meta));
            writeZipEntry(zos, "osv.jsonl", osvContent);
            writeZipEntry(zos, "depsdev.jsonl", depsdevContent);
            writeZipEntry(zos, "epss.jsonl", epssContent);
            writeZipEntry(zos, "kev.jsonl", kevContent);
            if (!unresolvedContent.isEmpty()) {
                writeZipEntry(zos, "unresolved.jsonl", unresolvedContent);
            }
        }
    }

    /** Full mode: every current line. Delta mode: only added/changed lines, plus a
     * {@code "_deleted":true} marker for every key the previous bundle had that this build
     * doesn't (see the class Javadoc). */
    private String renderContent(String filename, Map<String, String> currentByKey,
                                  PreviousBundleReader.PreviousBundle previous) {
        if (previous == null) {
            if (currentByKey.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String line : currentByKey.values()) sb.append(line).append('\n');
            return sb.toString();
        }
        Map<String, String> previousByKey = previous.linesByFileAndKey().getOrDefault(filename, Map.of());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : currentByKey.entrySet()) {
            String oldLine = previousByKey.get(e.getKey());
            if (oldLine == null || !oldLine.equals(e.getValue())) {
                sb.append(e.getValue()).append('\n');
            }
        }
        for (Map.Entry<String, String> e : previousByKey.entrySet()) {
            if (!currentByKey.containsKey(e.getKey())) {
                sb.append(buildDeleteMarker(filename, e.getValue())).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildDeleteMarker(String filename, String oldLineJson) {
        JsonNode old;
        try {
            old = mapper.readTree(oldLineJson);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable previous-bundle line: " + e.getMessage(), e);
        }
        ObjectNode marker = mapper.createObjectNode();
        switch (filename) {
            case "osv.jsonl", "unresolved.jsonl" -> {
                marker.put("ecosystem", old.path("ecosystem").asText(null));
                marker.put("name", old.path("name").asText(null));
                marker.put("version", old.path("version").asText(null));
            }
            case "depsdev.jsonl" -> {
                if ("advisory".equals(old.path("type").asText(null))) {
                    marker.put("type", "advisory");
                    marker.put("ghsaId", old.path("ghsaId").asText(null));
                } else {
                    marker.put("type", "version");
                    marker.put("ecosystem", old.path("ecosystem").asText(null));
                    marker.put("name", old.path("name").asText(null));
                    marker.put("version", old.path("version").asText(null));
                }
            }
            case "epss.jsonl", "kev.jsonl" -> marker.put("cveId", old.path("cveId").asText(null));
            default -> throw new IllegalStateException("Unknown delta file: " + filename);
        }
        marker.put("_deleted", true);
        return writeJson(marker);
    }

    private static int countLines(String content) {
        if (content.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n' && i != content.length() - 1) count++;
        }
        return count;
    }

    private void putSourceMeta(ObjectNode sources, String source, int records, LocalDate asOf, String origin) {
        ObjectNode s = sources.putObject(source);
        s.put("records", records);
        s.put("asOf", asOf.toString());
        s.put("origin", origin);
    }

    private void putFileMeta(ObjectNode files, String filename, String content, int lines) {
        ObjectNode f = files.putObject(filename);
        f.put("sha256", sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
        f.put("lines", lines);
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private String writeJson(Object node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
