package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * E5: {@code oswl-vdb} — builds/verifies/inspects offline vulnerability-DB (VDB) bundles from
 * live upstream sources (OSV, EPSS, CISA KEV, deps.dev), for import into an air-gapped OsWL
 * instance via {@code POST /api/admin/snapshot/import}.
 *
 * <p>Java CLI mode (the plan's recommended option (A) over a separate Python/Node script):
 * this reuses {@code AirgappedSnapshotService.componentKey()}/{@code normalizeEcosystem()}
 * directly, so the builder and the app can never disagree about key normalization.
 *
 * <p>Run via the {@code vdbBuild} Gradle task (see {@code scripts/oswl-vdb/oswl-vdb.{sh,ps1}} for
 * thin wrapper scripts): {@code ./gradlew vdbBuild --args="build --wanted wanted.jsonl --out bundle.zip"}
 *
 * <p><b>Not implemented in this pass</b> (see PERFORMANCE-AND-OFFLINE-PLAN.md E5's implementation
 * notes for why): {@code --mode delta}/{@code --since} (incremental bundles), {@code --offline-sources}
 * (build entirely from pre-downloaded files with no network at all — {@code --cache-dir} covers the
 * "don't re-download on every run" need but still requires network on a cold cache).
 */
public final class VdbBuilderCli {

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        int code = new VdbBuilderCli().run(args);
        if (code != 0) System.exit(code);
    }

    int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 1;
        }
        String command = args[0];
        List<String> rest = List.of(args).subList(1, args.length);
        try {
            return switch (command) {
                case "build" -> build(VdbBuildOptions.parse(rest));
                case "verify" -> verify(requirePositional(rest, "verify <bundle.zip>"));
                case "inspect" -> inspect(requirePositional(rest, "inspect <bundle.zip>"));
                default -> {
                    System.err.println("Unknown command: " + command);
                    printUsage();
                    yield 1;
                }
            };
        } catch (IllegalArgumentException e) {
            System.err.println("[oswl-vdb] " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.err.println("[oswl-vdb] Build failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    private static Path requirePositional(List<String> rest, String usage) {
        if (rest.isEmpty()) throw new IllegalArgumentException("Usage: oswl-vdb " + usage);
        return Path.of(rest.get(0));
    }

    private void printUsage() {
        System.err.println("""
                Usage:
                  oswl-vdb build --out <bundle.zip> [--wanted <wanted.jsonl>] [--sources osv,epss,kev,depsdev]
                                 [--ecosystems MAVEN,NPM,...] [--cache-dir <dir>]
                  oswl-vdb verify <bundle.zip>
                  oswl-vdb inspect <bundle.zip>
                """);
    }

    // ── build ────────────────────────────────────────────────────────────

    private int build(VdbBuildOptions opts) throws Exception {
        List<WantedComponent> wanted = opts.wantedList() != null ? loadWantedList(opts.wantedList()) : List.of();
        if (opts.wantedList() != null) {
            System.err.println("[oswl-vdb] Loaded " + wanted.size() + " wanted components from " + opts.wantedList());
        }

        HttpCache cache = new HttpCache(opts.cacheDir());
        boolean anyFailure = false;

        Map<String, List<com.salkcoding.oswl.service.AirgappedSnapshotService.SnapshotVuln>> osvVulns = Map.of();
        LocalDate osvAsOf = LocalDate.now();
        int unresolvedCount = 0;
        if (opts.sources().contains("osv")) {
            if (wanted.isEmpty()) {
                System.err.println("[oswl-vdb] WARNING: --sources includes osv but no --wanted was given — "
                        + "OSV bulk dumps are vuln-indexed, not component-indexed (E5.3); without a wanted-list "
                        + "there is no safe way to re-index them without a combinatorial explosion. Skipping osv.");
            } else {
                try {
                    OsvBulkSource.Result r = new OsvBulkSource(mapper).fetch(wanted, opts.ecosystems(), cache);
                    osvVulns = r.vulnsByComponentKey();
                    unresolvedCount = r.unresolvedComponentCount();
                    if (!r.asOfByBucket().isEmpty()) {
                        osvAsOf = r.asOfByBucket().values().stream().min(LocalDate::compareTo).orElse(LocalDate.now());
                    }
                } catch (Exception e) {
                    System.err.println("[oswl-vdb] osv source failed: " + e.getMessage());
                    anyFailure = true;
                }
            }
        }

        List<DepsDevSource.VersionRecord> depsdevVersions = List.of();
        List<DepsDevSource.AdvisoryRecord> depsdevAdvisories = List.of();
        if (opts.sources().contains("depsdev")) {
            if (wanted.isEmpty()) {
                System.err.println("[oswl-vdb] WARNING: --sources includes depsdev but no --wanted was given — "
                        + "deps.dev has no bulk dump (E5.2), only a per-package API. Skipping depsdev.");
            } else {
                try {
                    DepsDevSource.Result r = new DepsDevSource(mapper).fetch(wanted);
                    depsdevVersions = r.versions();
                    depsdevAdvisories = r.advisories();
                    if (r.failedVersionLookups() > 0) {
                        System.err.println("[oswl-vdb] deps.dev: " + r.failedVersionLookups() + " GetVersion lookups failed/not-found");
                    }
                } catch (Exception e) {
                    System.err.println("[oswl-vdb] depsdev source failed: " + e.getMessage());
                    anyFailure = true;
                }
            }
        }

        Map<String, Double> epssScores = Map.of();
        LocalDate epssAsOf = LocalDate.now();
        if (opts.sources().contains("epss")) {
            try {
                EpssSource.Result r = new EpssSource().fetch(cache);
                epssScores = r.scores();
                epssAsOf = r.asOf();
            } catch (Exception e) {
                System.err.println("[oswl-vdb] epss source failed: " + e.getMessage());
                anyFailure = true;
            }
        }

        java.util.Set<String> kevIds = java.util.Set.of();
        LocalDate kevAsOf = LocalDate.now();
        if (opts.sources().contains("kev")) {
            try {
                KevSource.Result r = new KevSource(mapper).fetch(cache);
                kevIds = r.cveIds();
                kevAsOf = r.asOf();
            } catch (Exception e) {
                System.err.println("[oswl-vdb] kev source failed: " + e.getMessage());
                anyFailure = true;
            }
        }

        new VdbBundleWriter(mapper).write(opts.out(), osvVulns, osvAsOf, depsdevVersions, depsdevAdvisories,
                epssScores, epssAsOf, kevIds, kevAsOf, unresolvedCount);

        System.err.println("[oswl-vdb] Wrote " + opts.out() + " — osv=" + osvVulns.size()
                + " depsdev-version=" + depsdevVersions.size() + " depsdev-advisory=" + depsdevAdvisories.size()
                + " epss=" + epssScores.size() + " kev=" + kevIds.size()
                + (unresolvedCount > 0 ? " (unresolvedComponents=" + unresolvedCount + ")" : ""));
        return anyFailure ? 1 : 0;
    }

    private List<WantedComponent> loadWantedList(Path path) throws IOException {
        List<WantedComponent> result = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode node = mapper.readTree(line);
            String ecosystem = node.path("ecosystem").asText(null);
            String name = node.path("name").asText(null);
            String version = node.path("version").asText(null);
            if (ecosystem == null || name == null || version == null) continue;
            result.add(new WantedComponent(ecosystem, name, version));
        }
        return result;
    }

    // ── verify ───────────────────────────────────────────────────────────

    private int verify(Path bundle) throws Exception {
        byte[] zipBytes = Files.readAllBytes(bundle);
        Map<String, byte[]> filesByName = new java.util.LinkedHashMap<>();
        byte[] metaBytes = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] content = zis.readAllBytes();
                if ("meta.json".equals(entry.getName())) metaBytes = content;
                else filesByName.put(entry.getName(), content);
            }
        }
        if (metaBytes == null) {
            System.err.println("[oswl-vdb] verify: no meta.json — cannot check checksums (v1/meta-less bundle)");
            return 1;
        }
        JsonNode meta = mapper.readTree(metaBytes);
        JsonNode files = meta.path("files");
        boolean ok = true;
        var fieldNames = files.fieldNames();
        while (fieldNames.hasNext()) {
            String filename = fieldNames.next();
            String expectedSha = files.path(filename).path("sha256").asText(null);
            byte[] content = filesByName.get(filename);
            if (content == null) {
                System.err.println("[oswl-vdb] verify: FAIL — " + filename + " listed in meta.json but missing from the zip");
                ok = false;
                continue;
            }
            String actualSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (!actualSha.equalsIgnoreCase(expectedSha)) {
                System.err.println("[oswl-vdb] verify: FAIL — " + filename + " checksum mismatch (expected " + expectedSha + ", got " + actualSha + ")");
                ok = false;
            } else {
                System.err.println("[oswl-vdb] verify: OK — " + filename);
            }
        }
        System.err.println(ok ? "[oswl-vdb] verify: bundle is valid" : "[oswl-vdb] verify: bundle FAILED checksum verification");
        return ok ? 0 : 1;
    }

    // ── inspect ──────────────────────────────────────────────────────────

    private int inspect(Path bundle) throws Exception {
        try (InputStream fis = Files.newInputStream(bundle);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("meta.json".equals(entry.getName())) {
                    JsonNode meta = mapper.readTree(zis.readAllBytes());
                    System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));
                    return 0;
                }
            }
        }
        System.err.println("[oswl-vdb] inspect: no meta.json in " + bundle + " (v1/meta-less bundle — nothing to show)");
        return 1;
    }
}
