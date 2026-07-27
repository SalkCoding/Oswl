package com.salkcoding.oswl.vdb;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Parsed {@code oswl-vdb build} arguments. See {@link VdbBuilderCli} for the full CLI surface and
 * for which options this first implementation does not (yet) support ({@code --mode delta},
 * {@code --since}, {@code --offline-sources} — see PERFORMANCE-AND-OFFLINE-PLAN.md E5's
 * implementation notes for why).
 */
public record VdbBuildOptions(
        Path out,
        Path wantedList,
        Set<String> sources,
        Set<String> ecosystems,
        Path cacheDir
) {
    static final List<String> ALL_SOURCES = List.of("osv", "epss", "kev", "depsdev");

    static VdbBuildOptions parse(List<String> args) {
        Path out = null;
        Path wanted = null;
        Set<String> sources = new java.util.LinkedHashSet<>(ALL_SOURCES);
        Set<String> ecosystems = null;
        Path cacheDir = null;
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
                    if (!"full".equalsIgnoreCase(v)) {
                        throw new IllegalArgumentException(
                                "--mode " + v + " is not supported yet — only 'full' is implemented "
                                        + "(see E5's implementation notes in PERFORMANCE-AND-OFFLINE-PLAN.md)");
                    }
                    i++;
                }
                case "--since", "--offline-sources" -> throw new IllegalArgumentException(
                        a + " is not supported yet (see E5's implementation notes in PERFORMANCE-AND-OFFLINE-PLAN.md)");
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
        return new VdbBuildOptions(out, wanted, sources, ecosystems, cacheDir);
    }

    private static String require(String v, String flag) {
        if (v == null) throw new IllegalArgumentException(flag + " requires a value");
        return v;
    }
}
