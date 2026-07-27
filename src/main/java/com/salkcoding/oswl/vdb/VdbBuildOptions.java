package com.salkcoding.oswl.vdb;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Parsed {@code oswl-vdb build} arguments. See {@link VdbBuilderCli} for the full CLI surface.
 *
 * <p>{@code --offline-sources <dir>} builds entirely without network access, from a directory
 * pre-populated by an earlier {@code --cache-dir} build run while online (see {@link HttpCache}).
 * It only covers the bulk-dumpable sources (osv/epss/kev) — {@code depsdev} has no bulk dump at
 * all (E5.2) and is always skipped when this flag is set, regardless of {@code --sources}.
 */
public record VdbBuildOptions(
        Path out,
        Path wantedList,
        Set<String> sources,
        Set<String> ecosystems,
        Path cacheDir,
        Path since,
        Path offlineSources
) {
    static final List<String> ALL_SOURCES = List.of("osv", "epss", "kev", "depsdev");

    static VdbBuildOptions parse(List<String> args) {
        Path out = null;
        Path wanted = null;
        Set<String> sources = new java.util.LinkedHashSet<>(ALL_SOURCES);
        Set<String> ecosystems = null;
        Path cacheDir = null;
        Path since = null;
        Path offlineSources = null;
        String mode = "full";
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String v = (i + 1 < args.size()) ? args.get(i + 1) : null;
            switch (a) {
                case "--out" -> { out = Path.of(require(v, "--out")); i++; }
                case "--wanted" -> { wanted = Path.of(require(v, "--wanted")); i++; }
                case "--sources" -> {
                    sources = new java.util.LinkedHashSet<>(java.util.Arrays.asList(require(v, "--sources").split(",")));
                    i++;
                }
                case "--ecosystems" -> {
                    ecosystems = new java.util.LinkedHashSet<>();
                    for (String e : require(v, "--ecosystems").split(",")) ecosystems.add(e.strip().toUpperCase(java.util.Locale.ROOT));
                    i++;
                }
                case "--cache-dir" -> { cacheDir = Path.of(require(v, "--cache-dir")); i++; }
                case "--mode" -> {
                    mode = require(v, "--mode").strip().toLowerCase(java.util.Locale.ROOT);
                    if (!mode.equals("full") && !mode.equals("delta")) {
                        throw new IllegalArgumentException("--mode must be 'full' or 'delta', got '" + mode + "'");
                    }
                    i++;
                }
                case "--since" -> { since = Path.of(require(v, "--since")); i++; }
                case "--offline-sources" -> { offlineSources = Path.of(require(v, "--offline-sources")); i++; }
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (out == null) {
            out = Path.of("oswl-vdb-" + java.time.LocalDate.now() + ".zip");
        }
        for (String s : sources) {
            if (!ALL_SOURCES.contains(s)) {
                throw new IllegalArgumentException("Unknown source '" + s + "' — expected one of " + ALL_SOURCES);
            }
        }
        if (mode.equals("delta") && since == null) {
            throw new IllegalArgumentException("--mode delta requires --since <previous-bundle.zip>");
        }
        if (since != null && mode.equals("full")) {
            mode = "delta"; // --since implies delta even if --mode wasn't spelled out
        }
        Path finalSince = mode.equals("delta") ? since : null;
        return new VdbBuildOptions(out, wanted, sources, ecosystems, cacheDir, finalSince, offlineSources);
    }

    boolean isDelta() {
        return since != null;
    }

    private static String require(String v, String flag) {
        if (v == null) throw new IllegalArgumentException(flag + " requires a value");
        return v;
    }
}
