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
 * all and is always skipped when this flag is set, regardless of {@code --sources}.
 */
public record VdbBuildOptions(
        Path out,
        Path wantedList,
        Set<String> sources,
        Set<String> ecosystems,
        Path cacheDir,
        Path since,
        Path offlineSources,
        String githubAdvisoryToken,
        String githubApiBase,
        String nvdApiKey,
        String distributionProfile
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
        String githubAdvisoryToken = System.getenv("OSWL_GITHUB_ADVISORY_TOKEN");
        String githubApiBase = System.getenv("OSWL_GITHUB_API_BASE");
        String nvdApiKey = System.getenv("OSWL_NVD_API_KEY");
        String mode = "full";
        String distributionProfile = "unreviewed";
        boolean explicitSources = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            String v = (i + 1 < args.size()) ? args.get(i + 1) : null;
            switch (a) {
                case "--distribution-profile" -> { distributionProfile = require(v, "--distribution-profile"); i++; }
                case "--out" -> { out = Path.of(require(v, "--out")); i++; }
                case "--wanted" -> { wanted = Path.of(require(v, "--wanted")); i++; }
                case "--sources" -> {
                    explicitSources = true;
                    sources = parseSelection(v, "--sources");
                    i++;
                }
                case "--ecosystems" -> {
                    ecosystems = new java.util.LinkedHashSet<>();
                    for (String e : parseSelection(v, "--ecosystems")) ecosystems.add(e.toUpperCase(java.util.Locale.ROOT));
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
                case "--github-advisory-token" -> throw new IllegalArgumentException("--github-advisory-token exposes secrets in process arguments; use OSWL_GITHUB_ADVISORY_TOKEN");
                case "--github-api-base" -> throw new IllegalArgumentException("--github-api-base is unavailable: GitHub CLI collection uses https://api.github.com");
                case "--nvd-api-key" -> throw new IllegalArgumentException("--nvd-api-key is unavailable: its collector is not connected to the bundle builder");
                default -> throw new IllegalArgumentException("Unknown option: " + a);
            }
        }
        if (!Set.of("unreviewed", "github-attributed").contains(distributionProfile))
            throw new IllegalArgumentException("Unknown distribution profile: " + distributionProfile);
        if (distributionProfile.equals("github-attributed")) {
            if (explicitSources && !sources.equals(Set.of("osv")))
                throw new IllegalArgumentException("github-attributed profile supports only --sources osv");
            if (wanted == null) throw new IllegalArgumentException("github-attributed profile requires --wanted");
            sources = Set.of("osv");
        }
        if (out == null) {
            out = Path.of("oswl-vdb-" + java.time.LocalDate.now() + ".zip");
        }
        for (String s : sources) {
            if (s.equals("nvd")) {
                throw new IllegalArgumentException("Source '" + s + "' is unavailable: its collector is not connected to the bundle builder");
            }
            if (!ALL_SOURCES.contains(s) && !s.equals("github-advisory")) {
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
        if (sources.contains("github-advisory")) {
            if (wanted == null) throw new IllegalArgumentException("GitHub collection requires --wanted");
            if (offlineSources != null) throw new IllegalArgumentException("GitHub collection cannot use --offline-sources");
            if (finalSince != null) throw new IllegalArgumentException("GitHub delta collection is not yet supported");
        }
        return new VdbBuildOptions(out, wanted, sources, ecosystems, cacheDir, finalSince, offlineSources,
                githubAdvisoryToken, githubApiBase, nvdApiKey, distributionProfile);
    }

    boolean isDelta() {
        return since != null;
    }

    private static Set<String> parseSelection(String value, String flag) {
        Set<String> selection = new java.util.LinkedHashSet<>();
        for (String entry : require(value, flag).split(",", -1)) {
            String item = entry.strip();
            if (item.isEmpty()) throw new IllegalArgumentException(flag + " requires a non-empty list without empty entries");
            selection.add(item);
        }
        return selection;
    }

    private static String require(String v, String flag) {
        if (v == null) throw new IllegalArgumentException(flag + " requires a value");
        return v;
    }
}
