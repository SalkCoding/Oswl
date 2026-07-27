package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.service.AirgappedSnapshotService.SnapshotVuln;

import java.io.ByteArrayOutputStream;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an {@code oswl-vdb} v2 bundle (E1 format) byte-for-byte compatible with what
 * {@code AirgappedSnapshotService.importBundle()} expects — see that class's Javadoc for the
 * exact JSONL line schemas this mirrors.
 */
final class VdbBundleWriter {

    private static final String BUNDLE_FORMAT = "oswl-vdb";
    private static final int FORMAT_VERSION = 2;
    static final String BUILDER_VERSION = "oswl-vdb/1.0.0";

    private final ObjectMapper mapper;

    VdbBundleWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(Path out,
               Map<String, List<SnapshotVuln>> osvByComponentKey, LocalDate osvAsOf,
               List<DepsDevSource.VersionRecord> depsdevVersions, List<DepsDevSource.AdvisoryRecord> depsdevAdvisories,
               Map<String, Double> epssScores, LocalDate epssAsOf,
               java.util.Set<String> kevCveIds, LocalDate kevAsOf,
               int unresolvedComponentCount) throws IOException {

        StringBuilder osvJsonl = new StringBuilder();
        for (Map.Entry<String, List<SnapshotVuln>> e : osvByComponentKey.entrySet()) {
            String[] parts = e.getKey().split("\\|", 3);
            if (parts.length != 3) continue;
            ObjectNode node = mapper.createObjectNode();
            node.put("ecosystem", parts[0]);
            node.put("name", parts[1]);
            node.put("version", parts[2]);
            node.putArray("vulns").addAll(e.getValue().stream().map(v -> (com.fasterxml.jackson.databind.JsonNode) mapper.valueToTree(v)).toList());
            osvJsonl.append(writeJson(node)).append('\n');
        }

        StringBuilder depsdevJsonl = new StringBuilder();
        for (DepsDevSource.VersionRecord v : depsdevVersions) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "version");
            node.put("ecosystem", v.ecosystem());
            node.put("name", v.name());
            node.put("version", v.version());
            node.putArray("licenses").addAll(v.licenses().stream().map(s -> (com.fasterxml.jackson.databind.JsonNode) mapper.valueToTree(s)).toList());
            node.putArray("advisoryKeys").addAll(v.advisoryKeys().stream().map(s -> (com.fasterxml.jackson.databind.JsonNode) mapper.valueToTree(s)).toList());
            node.put("isDefault", v.isDefault());
            if (v.deprecated() != null) node.put("deprecated", v.deprecated());
            depsdevJsonl.append(writeJson(node)).append('\n');
        }
        for (DepsDevSource.AdvisoryRecord a : depsdevAdvisories) {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", "advisory");
            node.put("ghsaId", a.ghsaId());
            if (a.title() != null) node.put("title", a.title());
            node.putArray("aliases").addAll(a.aliases().stream().map(s -> (com.fasterxml.jackson.databind.JsonNode) mapper.valueToTree(s)).toList());
            if (a.cvss3Score() != null) node.put("cvss3Score", a.cvss3Score());
            if (a.cvss3Vector() != null) node.put("cvss3Vector", a.cvss3Vector());
            depsdevJsonl.append(writeJson(node)).append('\n');
        }

        StringBuilder epssJsonl = new StringBuilder();
        epssScores.forEach((cveId, score) -> {
            ObjectNode node = mapper.createObjectNode();
            node.put("cveId", cveId);
            node.put("score", score);
            epssJsonl.append(writeJson(node)).append('\n');
        });

        StringBuilder kevJsonl = new StringBuilder();
        for (String cveId : kevCveIds) {
            kevJsonl.append("{\"cveId\":\"").append(cveId).append("\"}\n");
        }

        String osvContent = osvJsonl.toString();
        String depsdevContent = depsdevJsonl.toString();
        String epssContent = epssJsonl.toString();
        String kevContent = kevJsonl.toString();

        String bundleId = UUID.randomUUID().toString();
        LocalDateTime builtAt = LocalDateTime.now();

        ObjectNode meta = mapper.createObjectNode();
        meta.put("format", BUNDLE_FORMAT);
        meta.put("formatVersion", FORMAT_VERSION);
        meta.put("bundleId", bundleId);
        meta.put("mode", "full");
        meta.put("builtAt", builtAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("builder", BUILDER_VERSION);
        ObjectNode sources = meta.putObject("sources");
        putSourceMeta(sources, "osv", osvByComponentKey.size(), osvAsOf, "osv.dev bulk dump");
        putSourceMeta(sources, "depsdev-version", depsdevVersions.size(), LocalDate.now(), "deps.dev api (wanted-list)");
        putSourceMeta(sources, "depsdev-advisory", depsdevAdvisories.size(), LocalDate.now(), "deps.dev api");
        putSourceMeta(sources, "epss", epssScores.size(), epssAsOf, "epss current");
        putSourceMeta(sources, "kev", kevCveIds.size(), kevAsOf, "cisa kev");
        if (unresolvedComponentCount > 0) {
            ObjectNode coverage = meta.putObject("coverage");
            coverage.put("unresolvedComponents", unresolvedComponentCount);
            coverage.put("note", "These wanted components had at least one OSV range-typed "
                    + "affected[] entry this builder could not confidently evaluate (non-SEMVER "
                    + "range type, or an unparseable version string) — they are NOT necessarily "
                    + "vulnerability-free, just unresolved. See E5.3 in PERFORMANCE-AND-OFFLINE-PLAN.md.");
        }
        ObjectNode files = meta.putObject("files");
        putFileMeta(files, "osv.jsonl", osvContent, osvByComponentKey.size());
        putFileMeta(files, "depsdev.jsonl", depsdevContent, depsdevVersions.size() + depsdevAdvisories.size());
        putFileMeta(files, "epss.jsonl", epssContent, epssScores.size());
        putFileMeta(files, "kev.jsonl", kevContent, kevCveIds.size());

        Files.createDirectories(out.toAbsolutePath().getParent() != null ? out.toAbsolutePath().getParent() : out.toAbsolutePath());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            writeZipEntry(zos, "meta.json", writeJson(meta));
            writeZipEntry(zos, "osv.jsonl", osvContent);
            writeZipEntry(zos, "depsdev.jsonl", depsdevContent);
            writeZipEntry(zos, "epss.jsonl", epssContent);
            writeZipEntry(zos, "kev.jsonl", kevContent);
        }
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
