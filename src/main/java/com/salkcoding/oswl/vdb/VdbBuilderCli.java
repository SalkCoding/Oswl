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
 * {@code oswl-vdb} — builds/verifies/inspects offline vulnerability-DB (VDB) bundles from
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
 * <p>{@code --mode delta --since <previous-bundle.zip>} diffs the newly-built full dataset
 * against a previous bundle key-by-key (see {@link PreviousBundleReader}/{@link VdbBundleWriter}):
 * only added/changed lines are written, plus a {@code "_deleted":true} marker (the existing
 * delete-marker convention) for keys the previous bundle had that this build doesn't.
 *
 * <p>{@code --offline-sources <dir>} builds entirely without network access from a directory
 * pre-populated by an earlier {@code --cache-dir} run — see {@link HttpCache}'s offline-only mode.
 * Only covers osv/epss/kev (deps.dev has no bulk dump at all, so it's always skipped when
 * this flag is set).
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
                                 [--mode delta --since <previous-bundle.zip>]
                                 [--offline-sources <dir>]  (no network at all; osv/epss/kev only)
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

        boolean offlineMode = opts.offlineSources() != null;
        HttpCache cache = offlineMode
                ? new HttpCache(opts.offlineSources(), true)
                : new HttpCache(opts.cacheDir());
        java.util.Set<String> effectiveSources = opts.sources();
        if (offlineMode) {
            System.err.println("[oswl-vdb] --offline-sources " + opts.offlineSources()
                    + " — no network calls will be made; only osv/epss/kev are covered");
            if (effectiveSources.contains("depsdev")) {
                System.err.println("[oswl-vdb] WARNING: deps.dev has no bulk dump, so it cannot be built "
                        + "offline — skipping depsdev despite --sources including it.");
                effectiveSources = new java.util.LinkedHashSet<>(effectiveSources);
                effectiveSources.remove("depsdev");
            }
        }
        boolean anyFailure = false;

        Map<String, List<com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln>> osvVulns = Map.of();
        LocalDate osvAsOf = LocalDate.now();
        java.util.Set<String> osvUnresolvedKeys = java.util.Set.of();
        java.util.Set<String> osvProcessedEcosystems = java.util.Set.of();
        if (effectiveSources.contains("osv")) {
            if (wanted.isEmpty()) {
                System.err.println("[oswl-vdb] WARNING: --sources includes osv but no --wanted was given — "
                        + "OSV bulk dumps are vuln-indexed, not component-indexed; without a wanted-list "
                        + "there is no safe way to re-index them without a combinatorial explosion. Skipping osv.");
            } else {
                try {
                    OsvBulkSource.Result r = new OsvBulkSource(mapper).fetch(wanted, opts.ecosystems(), cache);
                    osvVulns = r.vulnsByComponentKey();
                    osvUnresolvedKeys = r.unresolvedKeys();
                    osvProcessedEcosystems = r.asOfByBucket().keySet();
                    if (!r.asOfByBucket().isEmpty()) {
                        osvAsOf = r.asOfByBucket().values().stream().min(LocalDate::compareTo).orElse(LocalDate.now());
                    }
                } catch (Exception e) {
                    System.err.println("[oswl-vdb] osv source failed: " + e.getMessage());
                    anyFailure = true;
                }
            }
        }
        int unresolvedCount = osvUnresolvedKeys.size();

        List<DepsDevSource.VersionRecord> depsdevVersions = List.of();
        List<DepsDevSource.AdvisoryRecord> depsdevAdvisories = List.of();
        Map<String, Integer> depsdevSkippedSystems = Map.of();
        if (effectiveSources.contains("depsdev")) {
            if (wanted.isEmpty()) {
                System.err.println("[oswl-vdb] WARNING: --sources includes depsdev but no --wanted was given — "
                        + "deps.dev has no bulk dump, only a per-package API. Skipping depsdev.");
            } else {
                try {
                    DepsDevSource.Result r = new DepsDevSource(mapper).fetch(wanted);
                    depsdevVersions = r.versions();
                    depsdevAdvisories = r.advisories();
                    depsdevSkippedSystems = r.skippedUnsupportedSystems();
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
        if (effectiveSources.contains("epss")) {
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
        if (effectiveSources.contains("kev")) {
            try {
                KevSource.Result r = new KevSource(mapper).fetch(cache);
                kevIds = r.cveIds();
                kevAsOf = r.asOf();
            } catch (Exception e) {
                System.err.println("[oswl-vdb] kev source failed: " + e.getMessage());
                anyFailure = true;
            }
        }

        VdbBundleWriter.WantedListInfo wantedListInfo = null;
        List<WantedComponent> unresolvedComponents = List.of();
        if (opts.wantedList() != null) {
            String wantedListId = sha256Hex(Files.readAllBytes(opts.wantedList()));
            Resolution resolution = partitionResolution(wanted, osvVulns.keySet(), osvUnresolvedKeys, osvProcessedEcosystems, depsdevVersions);
            unresolvedComponents = resolution.unresolved();
            wantedListInfo = new VdbBundleWriter.WantedListInfo(wantedListId, wanted.size(), resolution.resolvedCount());
        }

        PreviousBundleReader.PreviousBundle previous = null;
        if (opts.isDelta()) {
            System.err.println("[oswl-vdb] delta mode: diffing against " + opts.since());
            previous = PreviousBundleReader.read(opts.since(), mapper);
        }

        new VdbBundleWriter(mapper).write(opts.out(), osvVulns, osvAsOf, depsdevVersions, depsdevAdvisories,
                depsdevSkippedSystems,
                epssScores, epssAsOf, kevIds, kevAsOf, unresolvedCount, wantedListInfo, unresolvedComponents, previous);

        System.err.println("[oswl-vdb] Wrote " + opts.out() + " (" + (opts.isDelta() ? "delta" : "full") + " mode)"
                + " — osv=" + osvVulns.size()
                + " depsdev-version=" + depsdevVersions.size() + " depsdev-advisory=" + depsdevAdvisories.size()
                + " epss=" + epssScores.size() + " kev=" + kevIds.size()
                + (unresolvedCount > 0 ? " (unresolvedComponents=" + unresolvedCount + ")" : "")
                + (wantedListInfo != null ? " (wanted=" + wantedListInfo.wantedCount()
                        + " resolved=" + wantedListInfo.resolvedCount()
                        + " unresolved.jsonl=" + unresolvedComponents.size() + ")" : ""));
        return anyFailure ? 1 : 0;
    }

    private record Resolution(int resolvedCount, List<WantedComponent> unresolved) {}

    /** A wanted component counts as "resolved" if OSV or deps.dev actually produced an answer
     * for it — either found vulnerabilities, confirmed none, or resolved a deps.dev version. Only
     * genuinely unresolved (OSV range we couldn't evaluate, deps.dev lookup failed/skipped, or an
     * ecosystem OSV never even fetched a dump for) components are excluded — those are written to
     * {@code unresolved.jsonl} (see {@link VdbBundleWriter}) so the app can show "no data" instead
     * of implying "confirmed clean" for them. Since a fetched OSV ecosystem dump is complete, a
     * key whose ecosystem WAS processed and that's in neither {@code osvVulnKeys} nor
     * {@code osvUnresolvedKeys} was implicitly confirmed clean — it just never appeared in any
     * advisory's {@code affected[]} list. */
    private static Resolution partitionResolution(List<WantedComponent> wanted, java.util.Set<String> osvVulnKeys,
                                      java.util.Set<String> osvUnresolvedKeys, java.util.Set<String> osvProcessedEcosystems,
                                      List<DepsDevSource.VersionRecord> depsdevVersions) {
        java.util.Set<String> depsdevResolvedKeys = new java.util.HashSet<>();
        for (DepsDevSource.VersionRecord v : depsdevVersions) {
            String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(v.ecosystem(), v.name(), v.version());
            if (key != null) depsdevResolvedKeys.add(key);
        }
        int resolved = 0;
        List<WantedComponent> unresolved = new ArrayList<>();
        for (WantedComponent w : wanted) {
            String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(w.ecosystem(), w.name(), w.version());
            if (key == null) continue;
            String ecosystem = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.normalizeEcosystem(w.ecosystem());
            boolean osvResolved = osvVulnKeys.contains(key)
                    || (osvProcessedEcosystems.contains(ecosystem) && !osvUnresolvedKeys.contains(key));
            if (osvResolved || depsdevResolvedKeys.contains(key)) {
                resolved++;
            } else {
                unresolved.add(w);
            }
        }
        return new Resolution(resolved, unresolved);
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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
