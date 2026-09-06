package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.service.ingest.parser.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses dependency manifests from a project directory tree.
 * Shared by Quick Import and the CLI server-side parse API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DependencyManifestParserService {

    private final MavenBomVersionResolver bomVersionResolver;
    private final CondaPypiMappingService condaPypiMappingService;

    /**
     * SECURITY: executing mvnw/gradlew/dotnet from a cloned repository runs arbitrary repo
     * code on this server. Disabled by default; when false the parser falls back to static
     * manifest parsing (no transitive resolution via build tools).
     */
    @Value("${oswl.quick-import.allow-build-exec:false}")
    private boolean allowBuildExec;

    /** Console tools (mvnw, gradlew, npm) write human-readable output in the OS console codepage (e.g. MS949 on Korean Windows). */
    private static final Charset CONSOLE_CHARSET = Charset.forName(System.getProperty("native.encoding", "UTF-8"));

    /**
     * Every ecosystem tag this parser can emit on a {@code ComponentPayload}. Kept as an explicit
     * set (rather than derived from the literals scattered through the per-format parsers) so a
     * regression test can assert each tag is covered by the OSV ecosystem mapping — a tag missing
     * there would silently report "no vulnerabilities" for the whole ecosystem.
     */
    public static final Set<String> EMITTED_ECOSYSTEMS = Set.of(
            "MAVEN", "NPM", "PYPI", "GO", "CARGO", "NUGET", "RUBYGEMS", "COMPOSER",
            "CONAN", "VCPKG", "SUBMODULE", "VENDORED", "COCOAPODS", "CONDA");

    public record ParseResult(String ecosystem, List<ScanPayload.ComponentPayload> components) {}

    private record GradleComponent(String name, String version, List<List<ScanPayload.DependencyNodeRef>> paths) {}

    /** Outcome of an external process run: exit code, captured merged output, timeout flag. */
    private record ProcessOutput(int exitCode, String output, boolean timedOut) {}

    public ParseResult parseDependencies(Path cloneDir, String repoName) {
        return parseDependencies(cloneDir, repoName, null);
    }

    /**
     * @param manifestProgress optional callback receiving {@code (processedManifests, totalManifests)}
     *                         as each manifest group is handled — the manifest index knows the
     *                         full manifest count up front, so the caller can interpolate continuous
     *                         progress. Parsing must never fail because of reporting, so callback
     *                         exceptions are swallowed at debug level.
     */
    public ParseResult parseDependencies(Path cloneDir, String repoName,
                                         BiConsumer<Integer, Integer> manifestProgress) {
        List<ScanPayload.ComponentPayload> allComps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> ecosystems = new ArrayList<>();

        // walk the tree exactly once; every manifest lookup below hits this index.
        ManifestIndex index = buildIndex(cloneDir);
        log.debug("[DependencyParser] Manifest index built for '{}': {} basenames, {} suffixes",
                repoName, index.byFileName().size(), index.bySuffix().size());

        // progress denominator = every distinct indexed manifest; numerator = the ones
        // actually processed below (fallback groups skipped because a lock file already
        // resolved the ecosystem simply never count — the caller clamps monotonically).
        Set<Path> indexedManifests = new HashSet<>();
        index.byFileName().values().forEach(indexedManifests::addAll);
        index.bySuffix().values().forEach(indexedManifests::addAll);
        int totalManifests = Math.max(1, indexedManifests.size());
        Set<Path> processedManifests = new HashSet<>();
        java.util.function.Consumer<List<Path>> report = paths -> {
            if (manifestProgress == null) return;
            int before = processedManifests.size();
            processedManifests.addAll(paths);
            if (processedManifests.size() == before) return;
            try {
                manifestProgress.accept(processedManifests.size(), totalManifests);
            } catch (Exception e) {
                log.debug("[DependencyParser] progress callback failed for '{}': {}", repoName, e.getMessage());
            }
        };

        // ── Maven: walk all pom.xml files ──────────────────────────────────────
        List<Path> pomFiles = indexByNames(index, "pom.xml");
        if (!pomFiles.isEmpty()) {
            ecosystems.add("MAVEN");
            List<ScanPayload.ComponentPayload> mvnComps = runMvnDependencyList(cloneDir, repoName);
            if (mvnComps != null && !mvnComps.isEmpty()) {
                mergeComponents(allComps, seen, mvnComps, "MAVEN");
            } else {
                for (Path pom : pomFiles) {
                    mergeComponents(allComps, seen, parseSingleMavenPom(pom, cloneDir, repoName), "MAVEN");
                }
            }
            log.info("[DependencyParser][Multi] Maven: {} pom.xml → {} components so far", pomFiles.size(), allComps.size());
            report.accept(pomFiles);
        }

        // ── Gradle: build.gradle / build.gradle.kts ────────────────────────────
        List<Path> gradleFiles = indexByNames(index, "build.gradle", "build.gradle.kts");
        if (!gradleFiles.isEmpty()) {
            if (!ecosystems.contains("MAVEN")) ecosystems.add("MAVEN");
            List<ScanPayload.ComponentPayload> gradleDeps = runGradleDependencies(cloneDir, repoName);
            if (gradleDeps == null || gradleDeps.isEmpty()) {
                gradleDeps = parseGradleStatic(cloneDir, repoName).components();
            }
            if (gradleDeps != null && !gradleDeps.isEmpty()) {
                gradleDeps = bomVersionResolver.enrichComponentVersions(cloneDir, gradleDeps);
            }
            mergeComponents(allComps, seen, gradleDeps, "MAVEN");
            // Version catalogs cover libs.xxx references not captured by static regex
            mergeComponents(allComps, seen, parseVersionCatalogs(cloneDir, repoName, index), "MAVEN");
            log.info("[DependencyParser][Multi] Gradle: {} build files → {} components so far", gradleFiles.size(), allComps.size());
            report.accept(gradleFiles);
        }

        // ── npm: lock files first (full transitive), then package.json ──────────
        for (Path lock : indexByNames(index, "package-lock.json", "yarn.lock", "pnpm-lock.yaml")) {
            String fn = lock.getFileName().toString();
            Path d = lock.getParent();
            List<ScanPayload.ComponentPayload> npmComps = switch (fn) {
                case "package-lock.json" -> parseNpmLock(d, repoName);
                case "yarn.lock"         -> parseYarnLock(d, repoName);
                case "pnpm-lock.yaml"    -> parsePnpmLock(d, repoName);
                default                  -> null;
            };
            if (npmComps != null && !npmComps.isEmpty()) {
                if (!ecosystems.contains("NPM")) ecosystems.add("NPM");
                mergeComponents(allComps, seen, npmComps, "NPM");
            }
            report.accept(List.of(lock));
        }
        if (!ecosystems.contains("NPM")) {
            indexByNames(index, "package.json").stream()
                    .min(Comparator.comparingInt(p -> cloneDir.relativize(p).getNameCount()))
                    .ifPresent(pkg -> {
                        List<ScanPayload.ComponentPayload> npmComps =
                                runNpmPackageLockOnly(pkg.getParent(), repoName);
                        if (npmComps != null && !npmComps.isEmpty()) {
                            ecosystems.add("NPM");
                            mergeComponents(allComps, seen, npmComps, "NPM");
                        }
                        report.accept(List.of(pkg));
                    });
        }
        if (!ecosystems.contains("NPM")) {
            for (Path pkg : indexByNames(index, "package.json")) {
                List<ScanPayload.ComponentPayload> npmComps = parseNpmPackageJson(pkg.getParent(), repoName).components();
                if (!npmComps.isEmpty()) {
                    ecosystems.add("NPM");
                    mergeComponents(allComps, seen, npmComps, "NPM");
                }
                report.accept(List.of(pkg));
            }
        }

        // ── Python: lock files first, then requirements.txt ────────────────────
        for (Path lock : indexByNames(index, "poetry.lock", "uv.lock", "Pipfile.lock")) {
            String fn = lock.getFileName().toString();
            List<ScanPayload.ComponentPayload> pyComps = fn.equals("Pipfile.lock")
                    ? parsePipfileLock(lock.getParent(), repoName)
                    : parseTomlPackageLock(lock, "PYPI", repoName);
            if (pyComps != null && !pyComps.isEmpty()) {
                if (!ecosystems.contains("PYPI")) ecosystems.add("PYPI");
                mergeComponents(allComps, seen, pyComps, "PYPI");
            }
            report.accept(List.of(lock));
        }
        if (!ecosystems.contains("PYPI")) {
            for (Path req : indexByNames(index, "requirements.txt")) {
                List<ScanPayload.ComponentPayload> pyComps =
                        parseRequirementsFile(req, repoName);
                if (pyComps != null && !pyComps.isEmpty()) {
                    if (!ecosystems.contains("PYPI")) ecosystems.add("PYPI");
                    mergeComponents(allComps, seen, pyComps, "PYPI");
                }
                report.accept(List.of(req));
            }
            if (!ecosystems.contains("PYPI")) {
                for (Path toml : indexByNames(index, "pyproject.toml")) {
                    List<ScanPayload.ComponentPayload> pyComps =
                            parsePyprojectToml(toml, repoName);
                    if (pyComps != null && !pyComps.isEmpty()) {
                        ecosystems.add("PYPI");
                        mergeComponents(allComps, seen, pyComps, "PYPI");
                    }
                    report.accept(List.of(toml));
                }
            }
        }

        // ── Cargo: Cargo.lock, then Cargo.toml fallback ────────────────────────
        for (Path lock : indexByNames(index, "Cargo.lock")) {
            List<ScanPayload.ComponentPayload> cargoComps = parseTomlPackageLock(lock, "CARGO", repoName);
            if (cargoComps != null && !cargoComps.isEmpty()) {
                if (!ecosystems.contains("CARGO")) ecosystems.add("CARGO");
                mergeComponents(allComps, seen, cargoComps, "CARGO");
            }
            report.accept(List.of(lock));
        }
        if (!ecosystems.contains("CARGO")) {
            for (Path toml : indexByNames(index, "Cargo.toml")) {
                List<ScanPayload.ComponentPayload> cargoComps = parseCargoToml(toml.getParent(), repoName);
                if (!cargoComps.isEmpty()) {
                    ecosystems.add("CARGO");
                    mergeComponents(allComps, seen, cargoComps, "CARGO");
                }
                report.accept(List.of(toml));
            }
        }

        // ── Go: go.sum, then go.mod fallback ───────────────────────────────────
        for (Path sum : indexByNames(index, "go.sum")) {
            List<ScanPayload.ComponentPayload> goComps = parseGoSum(sum.getParent(), repoName);
            if (goComps != null && !goComps.isEmpty()) {
                if (!ecosystems.contains("GO")) ecosystems.add("GO");
                mergeComponents(allComps, seen, goComps, "GO");
            }
            report.accept(List.of(sum));
        }
        if (!ecosystems.contains("GO")) {
            for (Path gomod : indexByNames(index, "go.mod")) {
                List<ScanPayload.ComponentPayload> goComps = parseGoModDeclared(gomod.getParent(), repoName);
                if (!goComps.isEmpty()) {
                    ecosystems.add("GO");
                    mergeComponents(allComps, seen, goComps, "GO");
                }
                report.accept(List.of(gomod));
            }
        }

        // ── NuGet: packages.lock.json / .csproj ────────────────────────────────
        for (Path lock : indexByNames(index, "packages.lock.json")) {
            List<ScanPayload.ComponentPayload> nugetComps = parseNuGetLockFile(lock.getParent(), repoName);
            if (nugetComps != null && !nugetComps.isEmpty()) {
                if (!ecosystems.contains("NUGET")) ecosystems.add("NUGET");
                mergeComponents(allComps, seen, nugetComps, "NUGET");
            }
            report.accept(List.of(lock));
        }
        if (!ecosystems.contains("NUGET") && hasCsprojFiles(index)) {
            List<ScanPayload.ComponentPayload> nugetComps = runDotNetListPackages(cloneDir, repoName, index);
            if (nugetComps == null || nugetComps.isEmpty()) {
                nugetComps = parseNuGetStatic(cloneDir, repoName, index).components();
            }
            if (!nugetComps.isEmpty()) {
                ecosystems.add("NUGET");
                mergeComponents(allComps, seen, nugetComps, "NUGET");
            }
            report.accept(indexBySuffix(index, ".csproj"));
        }

        // ── Ruby: Gemfile.lock ────────────────────────────────────────────────
        for (Path lock : indexByNames(index, "Gemfile.lock")) {
            List<ScanPayload.ComponentPayload> rubyComps = parseGemfileLock(lock.getParent(), repoName);
            if (rubyComps != null && !rubyComps.isEmpty()) {
                if (!ecosystems.contains("RUBYGEMS")) ecosystems.add("RUBYGEMS");
                mergeComponents(allComps, seen, rubyComps, "RUBYGEMS");
            }
            report.accept(List.of(lock));
        }

        // ── PHP: composer.lock ────────────────────────────────────────────────
        for (Path lock : indexByNames(index, "composer.lock")) {
            List<ScanPayload.ComponentPayload> composerComps = parseComposerLock(lock.getParent(), repoName);
            if (composerComps != null && !composerComps.isEmpty()) {
                if (!ecosystems.contains("COMPOSER")) ecosystems.add("COMPOSER");
                mergeComponents(allComps, seen, composerComps, "COMPOSER");
            }
            report.accept(List.of(lock));
        }

        // ── C/C++: conan.lock ─────────────────────────────────────────────────
        for (Path lock : indexByNames(index, "conan.lock")) {
            List<ScanPayload.ComponentPayload> conanComps = parseConanLock(lock.getParent(), repoName);
            if (conanComps != null && !conanComps.isEmpty()) {
                if (!ecosystems.contains("CONAN")) ecosystems.add("CONAN");
                mergeComponents(allComps, seen, conanComps, "CONAN");
            }
            report.accept(List.of(lock));
        }

        // ── C/C++: vcpkg.json ─────────────────────────────────────────────────
        for (Path vcpkg : indexByNames(index, "vcpkg.json", "vcpkg-configuration.json")) {
            String fn = vcpkg.getFileName().toString();
            List<ScanPayload.ComponentPayload> vcpkgComps = "vcpkg.json".equals(fn)
                    ? parseVcpkgJson(vcpkg.getParent(), repoName)
                    : parseVcpkgConfigurationJson(vcpkg.getParent(), repoName);
            if (vcpkgComps != null && !vcpkgComps.isEmpty()) {
                if (!ecosystems.contains("VCPKG")) ecosystems.add("VCPKG");
                mergeComponents(allComps, seen, vcpkgComps, "VCPKG");
            }
            report.accept(List.of(vcpkg));
        }

        // ── C/C++: git submodules (.gitmodules + git ls-tree pinned SHA) ───────
        for (Path gitmodules : indexByNames(index, ".gitmodules")) {
            List<ScanPayload.ComponentPayload> subComps = parseGitSubmodules(gitmodules.getParent(), repoName);
            if (subComps != null && !subComps.isEmpty()) {
                if (!ecosystems.contains("SUBMODULE")) ecosystems.add("SUBMODULE");
                mergeComponents(allComps, seen, subComps, "SUBMODULE");
            }
            report.accept(List.of(gitmodules));
        }

        // ── C/C++: vendored dependencies in CMakeLists.txt ──────────────────────
        for (Path cmake : indexByNames(index, "CMakeLists.txt")) {
            List<ScanPayload.ComponentPayload> vendoredComps = parseCMakeLists(cmake.getParent(), repoName);
            if (vendoredComps != null && !vendoredComps.isEmpty()) {
                if (!ecosystems.contains("VENDORED")) ecosystems.add("VENDORED");
                mergeComponents(allComps, seen, vendoredComps, "VENDORED");
            }
            report.accept(List.of(cmake));
        }

        // ── iOS/macOS: Podfile.lock (CocoaPods) ─────────────────────────────────
        for (Path lock : indexByNames(index, "Podfile.lock")) {
            List<ScanPayload.ComponentPayload> podComps = parsePodfileLock(lock.getParent(), repoName);
            if (podComps != null && !podComps.isEmpty()) {
                if (!ecosystems.contains("COCOAPODS")) ecosystems.add("COCOAPODS");
                mergeComponents(allComps, seen, podComps, "COCOAPODS");
            }
            report.accept(List.of(lock));
        }

        // ── Data science: conda-lock.yml (Conda) ────────────────────────────────
        for (Path lock : indexByNames(index, "conda-lock.yml")) {
            List<ScanPayload.ComponentPayload> condaComps = parseCondaLock(lock.getParent(), repoName);
            if (condaComps != null && !condaComps.isEmpty()) {
                for (ScanPayload.ComponentPayload c : condaComps) {
                    if (!ecosystems.contains(c.getEcosystem())) ecosystems.add(c.getEcosystem());
                }
                mergeComponents(allComps, seen, condaComps, "CONDA");
            }
            report.accept(List.of(lock));
        }

        // ── Data science: pixi.lock (pixi/Conda) ────────────────────────────────
        for (Path lock : indexByNames(index, "pixi.lock")) {
            List<ScanPayload.ComponentPayload> pixiComps = parsePixiLock(lock.getParent(), repoName);
            if (pixiComps != null && !pixiComps.isEmpty()) {
                for (ScanPayload.ComponentPayload c : pixiComps) {
                    if (!ecosystems.contains(c.getEcosystem())) ecosystems.add(c.getEcosystem());
                }
                mergeComponents(allComps, seen, pixiComps, "CONDA");
            }
            report.accept(List.of(lock));
        }

        // ── Container: explicitly version-pinned OS packages in Dockerfile ──────
        for (Path dockerfile : indexByNames(index, "Dockerfile")) {
            List<ScanPayload.ComponentPayload> osComps = parseDockerfile(dockerfile, repoName);
            if (osComps != null && !osComps.isEmpty()) {
                if (!ecosystems.contains("OS_PACKAGE")) ecosystems.add("OS_PACKAGE");
                mergeComponents(allComps, seen, osComps, "OS_PACKAGE");
            }
            report.accept(List.of(dockerfile));
        }

        if (allComps.isEmpty()) {
            log.warn("[DependencyParser] No recognized manifests in '{}' — empty component list.", repoName);
            return new ParseResult("UNKNOWN", List.of());
        }
        String primary = ecosystems.getFirst();
        log.info("[DependencyParser] Multi-scan '{}': {} components across ecosystems={}", repoName, allComps.size(), ecosystems);
        return new ParseResult(primary, allComps);
    }

    /** Returns text content of the first direct child element with the given tag name, or null. */
    private String getDirectChildText(Element parent, String tag) {
        return new MavenPomParser(bomVersionResolver).getDirectChildText(parent, tag);
    }

    /** Returns the first direct child element with the given tag name, or null. */
    private Element getDirectChild(Element parent, String tag) {
        return new MavenPomParser(bomVersionResolver).getDirectChild(parent, tag);
    }

    /** Resolves ${prop} placeholders against the given property map. Returns unchanged if unresolved. */
    private String resolveProp(String raw, Map<String, String> props) {
        return new MavenPomParser(bomVersionResolver).resolveProp(raw, props);
    }

    // ── Multi-manifest helpers ─────────────────────────────────────────────

    /** Parses a single {@code pom.xml}'s direct dependencies, tagging non-runtime scope (test/provided/system). */
    private List<ScanPayload.ComponentPayload> parseSingleMavenPom(Path pomFile, Path projectDir, String repoName) {
        return new MavenPomParser(bomVersionResolver).parseSingleMavenPom(pomFile, projectDir, repoName);
    }

    /**
     * Attempts {@code ./mvnw dependency:list} for full transitive Maven resolution.
     * Returns {@code null} if the wrapper is absent, exits non-zero, or times out.
     */
    private List<ScanPayload.ComponentPayload> runMvnDependencyList(Path dir, String repoName) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = dir.resolve(isWindows ? "mvnw.cmd" : "mvnw");
        if (!Files.exists(wrapper)) {
            log.debug("[DependencyParser][Maven] No mvnw in '{}', using static pom.xml parse", repoName);
            return null;
        }
        if (!allowBuildExec) {
            log.warn("[DependencyParser][Maven] mvnw found in '{}' but build execution is disabled "
                    + "(oswl.quick-import.allow-build-exec=false) — using static pom.xml parse", repoName);
            return null;
        }
        try {
            if (!isWindows) wrapper.toFile().setExecutable(true, false);
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", wrapper.toString(),
                              "dependency:list", "-DincludeScope=runtime", "-q", "--batch-mode")
                    : List.of(wrapper.toString(),
                              "dependency:list", "-DincludeScope=runtime", "-q", "--batch-mode");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isBlank()) pb.environment().put("JAVA_HOME", javaHome);
            log.info("[DependencyParser][Maven] Running mvnw dependency:list for '{}'", repoName);
            ProcessOutput result = runProcess(pb, 5, TimeUnit.MINUTES, CONSOLE_CHARSET);
            if (result.timedOut()) {
                log.warn("[DependencyParser][Maven] mvnw timed out for '{}' — partial output: {}",
                        repoName, summarizeProcessOutput(result.output()));
                return null;
            }
            if (result.exitCode() != 0) {
                log.warn("[DependencyParser][Maven] mvnw exited {} for '{}', falling back to static parse", result.exitCode(), repoName);
                return null;
            }
            String output = result.output();
            // [INFO]    groupId:artifactId:jar:version:scope
            Pattern lineP = Pattern.compile("^\\[INFO\\]\\s+([\\w.\\-]+:[\\w.\\-]+):[\\w.\\-]+:([\\w.+\\-]+):(compile|runtime)\\s*$");
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String line : output.split("\r?\n")) {
                Matcher m = lineP.matcher(line.trim());
                if (m.matches()) {
                    String coord = m.group(1); String version = m.group(2);
                    if (seen.add(coord + ":" + version)) comps.add(buildComponent(coord, version, "MAVEN"));
                }
            }
            log.info("[DependencyParser][Maven] mvnw → {} components for '{}'", comps.size(), repoName);
            return comps.isEmpty() ? null : comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Maven] mvnw failed for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /**
     * Single-walk manifest index over the clone tree. Built once per
     * {@link #parseDependencies} call; all per-ecosystem manifest lookups read
     * from it instead of re-walking the tree.
     *
     * @param root       resolved clone root the indexed paths sit under
     * @param byFileName basename → paths (e.g. "pom.xml"), each list sorted by path string
     * @param bySuffix   suffix → paths (e.g. ".csproj"), each list sorted by path string
     */


    /**
     * Walks the tree under {@code root} exactly once and indexes every file whose
     * basename is in {@link ManifestCollectRules#EXACT_FILE_NAMES} or ends with one of
     * {@link ManifestCollectRules#FILE_SUFFIXES}, skipping any path segment listed in
     * {@link ManifestCollectRules#SKIP_DIRS}. Index lists are sorted by path string so
     * iteration order is deterministic.
     */
    private ManifestIndex buildIndex(Path root) {
        return new ManifestDiscovery().buildIndex(root);
    }

    /** Returns indexed paths for the given exact basenames, merged and sorted by path string. */
    private List<Path> indexByNames(ManifestIndex index, String... fileNames) {
        return new ManifestDiscovery().indexByNames(index, fileNames);
    }

    /** Returns indexed paths whose basename ends with {@code suffix} (already path-sorted). */
    private List<Path> indexBySuffix(ManifestIndex index, String suffix) {
        return new ManifestDiscovery().indexBySuffix(index, suffix);
    }

    /** Mirrors the legacy {@code Files.walk(dir, 8)} depth limit on top of the shared index. */
    private boolean hasCsprojFiles(ManifestIndex index) {
        return new ManifestDiscovery().hasCsprojFiles(index);
    }

    /** Merges {@code src} into {@code target}, deduplicating on name+version+ecosystem. */
    private void mergeComponents(List<ScanPayload.ComponentPayload> target,
                                  Set<String> seen,
                                  List<ScanPayload.ComponentPayload> src,
                                  String fallbackEcosystem) {
        if (src == null) return;
        for (ScanPayload.ComponentPayload c : src) {
            String key = (c.getName() != null ? c.getName() : "?") + ":"
                    + (c.getVersion() != null ? c.getVersion() : "") + ":"
                    + (c.getEcosystem() != null ? c.getEcosystem() : fallbackEcosystem);
            if (seen.add(key)) target.add(c);
        }
    }

    /**
     * Parses Gradle version catalog files ({@code *.versions.toml}) found up to 3 levels deep.
     * Resolves {@code version.ref} references and returns all {@code [libraries]} entries as
     * Maven ecosystem components (Gradle uses Maven coordinates).
     */
    private List<ScanPayload.ComponentPayload> parseVersionCatalogs(Path dir, String repoName, ManifestIndex index) {
        return new VersionCatalogParser().parseVersionCatalogs(dir, repoName, index);
    }

    /**
     * Generates {@code package-lock.json} via {@code npm install --package-lock-only} when no lock
     * file exists and npm is on PATH. Returns parsed components or {@code null} on failure.
     */
    private List<ScanPayload.ComponentPayload> runNpmPackageLockOnly(Path dir, String repoName) {
        if (!Files.isRegularFile(dir.resolve("package.json"))) {
            return null;
        }
        if (Files.exists(dir.resolve("package-lock.json"))
                || Files.exists(dir.resolve("yarn.lock"))
                || Files.exists(dir.resolve("pnpm-lock.yaml"))) {
            return null;
        }
        if (!isCommandOnPath("npm")) {
            log.debug("[DependencyParser][npm] npm not on PATH for '{}', skipping lock generation", repoName);
            return null;
        }
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String npmArgs = "npm install --package-lock-only --ignore-scripts --no-audit --no-fund --no-progress";
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", npmArgs)
                    : List.of("npm", "install", "--package-lock-only",
                            "--ignore-scripts", "--no-audit", "--no-fund", "--no-progress");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            log.info("[DependencyParser][npm] Running npm install --package-lock-only for '{}'", repoName);
            ProcessOutput result = runProcess(pb, 5, TimeUnit.MINUTES, CONSOLE_CHARSET);
            if (result.timedOut()) {
                log.warn("[DependencyParser][npm] npm lock generation timed out for '{}' — partial output: {}",
                        repoName, summarizeProcessOutput(result.output()));
                return null;
            }
            if (result.exitCode() != 0) {
                log.warn("[DependencyParser][npm] npm exited {} for '{}' — {}",
                        result.exitCode(), repoName, summarizeProcessOutput(result.output()));
                return null;
            }
            if (!Files.exists(dir.resolve("package-lock.json"))) {
                log.warn("[DependencyParser][npm] npm finished but no package-lock.json for '{}'", repoName);
                return null;
            }
            List<ScanPayload.ComponentPayload> comps = parseNpmLock(dir, repoName);
            log.info("[DependencyParser][npm] Generated lock → {} components for '{}'",
                    comps != null ? comps.size() : 0, repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] npm lock generation failed for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /** Parse package-lock.json — supports lockfileVersion 1 (npm 5/6), 2 and 3 (npm 7+). */
    private List<ScanPayload.ComponentPayload> parseNpmLock(Path dir, String repoName) {
        return new NpmManifestParser().parseNpmLock(dir, repoName);
    }

    /** Fallback: parse package.json declared deps only (no transitive). */
    private ParseResult parseNpmPackageJson(Path dir, String repoName) {
        return new NpmManifestParser().parseNpmPackageJson(dir, repoName);
    }

    private void addNpmDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode, String scope) {
        new NpmManifestParser().addNpmDeps(comps, depsNode, scope);
    }

    /**
     * Parses {@code yarn.lock} (classic / v1 format). Each entry header may list multiple
     * descriptor strings (e.g. {@code "@scope/pkg@^1.0", "@scope/pkg@~1.1":}) followed by
     * an indented body containing {@code version "X.Y.Z"}. Same package may appear under
     * multiple resolved versions — we keep all distinct (name, version) pairs.
     */
    private List<ScanPayload.ComponentPayload> parseYarnLock(Path dir, String repoName) {
        return new NpmManifestParser().parseYarnLock(dir, repoName);
    }

    /**
     * Parses {@code pnpm-lock.yaml}. Supports v6+ (where {@code packages:} keys look like
     * {@code /name@version} or {@code 'name@version'}) and older v5 ({@code /name/version}).
     */
    private List<ScanPayload.ComponentPayload> parsePnpmLock(Path dir, String repoName) {
        return new NpmManifestParser().parsePnpmLock(dir, repoName);
    }

    /**
     * Runs {@code gradlew dependencies --configuration runtimeClasspath -q --no-daemon} in the
     * cloned directory to obtain the fully-resolved transitive dependency tree.
     * Returns {@code null} if the wrapper is absent, times out, or exits non-zero.
     */
    private List<ScanPayload.ComponentPayload> runGradleDependencies(Path dir, String repoName) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path wrapper = dir.resolve(isWindows ? "gradlew.bat" : "gradlew");
        if (!Files.exists(wrapper)) {
            log.info("[DependencyParser][Gradle] No gradlew found in '{}', falling back to static parse", repoName);
            return null;
        }
        if (!allowBuildExec) {
            log.warn("[DependencyParser][Gradle] gradlew found in '{}' but build execution is disabled "
                    + "(oswl.quick-import.allow-build-exec=false) — using static parse", repoName);
            return null;
        }
        try {
            if (!isWindows) {
                wrapper.toFile().setExecutable(true, false);
            }
            List<String> baseArgs = isWindows
                    ? List.of("cmd", "/c", wrapper.toString(), "dependencies", "-q", "--no-daemon")
                    : List.of(wrapper.toString(), "dependencies", "-q", "--no-daemon");
            // Try configurations in priority order; fall through on failure or empty result
            List<String> configurations = List.of("runtimeClasspath", "compileClasspath");
            String javaHome = System.getProperty("java.home");
            for (String config : configurations) {
                List<String> cmd = new ArrayList<>(baseArgs);
                cmd.add("--configuration"); cmd.add(config);
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
                if (javaHome != null && !javaHome.isBlank()) {
                    pb.environment().put("JAVA_HOME", javaHome);
                    String pathSep = isWindows ? ";" : ":";
                    pb.environment().merge("PATH", javaHome + File.separator + "bin",
                            (existing, added) -> added + pathSep + existing);
                }
                log.info("[DependencyParser][Gradle] Running gradlew dependencies --configuration {} for '{}'", config, repoName);
                ProcessOutput result = runProcess(pb, 5, TimeUnit.MINUTES, CONSOLE_CHARSET);
                if (result.timedOut()) {
                    log.warn("[DependencyParser][Gradle] gradlew timed out for '{}' — partial output: {}",
                            repoName, summarizeProcessOutput(result.output()));
                    return null;
                }
                if (result.exitCode() != 0) {
                    log.warn("[DependencyParser][Gradle] gradlew --configuration {} exited {} for '{}' — {}",
                            config, result.exitCode(), repoName, summarizeProcessOutput(result.output()));
                    continue;
                }
                List<ScanPayload.ComponentPayload> comps = parseGradleTreeOutput(result.output(), repoName);
                if (!comps.isEmpty()) {
                    log.info("[DependencyParser][Gradle] gradlew --configuration {} resolved {} components for '{}'", config, comps.size(), repoName);
                    return comps;
                }
            }
            log.warn("[DependencyParser][Gradle] gradlew produced no components for '{}', falling back to static parse", repoName);
            return null;
        } catch (Exception e) {
            log.warn("[DependencyParser][Gradle] Failed to run gradlew for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code gradle dependencies} tree output into a flat component list.
     * Handles version conflict markers ({@code ->}), already-expanded markers ({@code (*)}),
     * and multi-level transitive paths.
     */
    private List<ScanPayload.ComponentPayload> parseGradleTreeOutput(String output, String repoName) {
        String projectName = repoName.contains("/")
                ? repoName.substring(repoName.lastIndexOf('/') + 1) : repoName;
        Map<String, GradleComponent> depComps = new LinkedHashMap<>();
        Map<Integer, String[]> depStack = new HashMap<>(); // depth → [compName, compVer]

        for (String rawLine : output.split("\r?\n")) {
            int pos = rawLine.indexOf("+---");
            if (pos < 0) pos = rawLine.indexOf("\\---");
            if (pos < 0) continue;

            int depth = pos / 5;
            String suffix = rawLine.substring(pos + 5).trim();
            suffix = suffix.replaceAll("\\s*\\(\\*\\)\\s*$", "").trim(); // strip (*)
            if (suffix.isBlank()) continue;

            // version conflict resolution: "g:a:oldVer -> newVer"
            String resolvedVer = null;
            if (suffix.contains(" -> ")) {
                int arrow = suffix.lastIndexOf(" -> ");
                resolvedVer = suffix.substring(arrow + 4).trim().replaceAll("[\\s*()]", "");
                suffix = suffix.substring(0, arrow).trim();
            }

            String[] parts = suffix.split(":");
            if (parts.length < 2) continue;
            String compName = parts[0].trim() + ":" + parts[1].trim();
            String compVer  = resolvedVer != null ? resolvedVer
                    : (parts.length >= 3 ? parts[2].replaceAll("[\\s*()]", "").trim() : null);

            depStack.put(depth, new String[]{compName, compVer});

            // Build one representative dependency path
            List<ScanPayload.DependencyNodeRef> path = new ArrayList<>();
            path.add(ScanPayload.DependencyNodeRef.create(projectName, "local"));
            for (int lvl = 0; lvl < depth; lvl++) {
                String[] anc = depStack.get(lvl);
                if (anc != null) path.add(ScanPayload.DependencyNodeRef.create(anc[0], anc[1]));
            }
            path.add(ScanPayload.DependencyNodeRef.create(compName, compVer));

            String key = compName + ":" + (compVer != null ? compVer : "");
            depComps.computeIfAbsent(key, _ -> new GradleComponent(compName, compVer, new ArrayList<>()))
                    .paths().add(path);
        }

        List<ScanPayload.ComponentPayload> result = new ArrayList<>();
        for (GradleComponent gc : depComps.values()) {
            boolean hasDirect = gc.paths().stream().anyMatch(p -> p.size() == 2);
            boolean hasTransitive = gc.paths().stream().anyMatch(p -> p.size() > 2);
            long directCount = gc.paths().stream().filter(p -> p.size() == 2).count();
            long transitiveCount = gc.paths().stream().filter(p -> p.size() > 2).count();
            String info;
            if (hasDirect && hasTransitive) {
                info = "Direct (" + directCount + ") + Transitive (" + transitiveCount + ")";
            } else if (hasDirect) {
                info = "Direct (" + directCount + ")";
            } else {
                info = "Transitive (" + transitiveCount + ")";
            }
            result.add(ScanPayload.ComponentPayload.create(
                    gc.name(), gc.version(), "MAVEN", info, gc.paths()));
        }
        return result;
    }

    /**
     * Runs an external process, draining its merged stdout/stderr on a separate reader thread
     * (same pattern as {@code GitCloneExecutor}). Reading inline before {@code waitFor} would
     * block until process exit and defeat the timeout. On timeout the process is destroyed
     * forcibly and whatever partial output was captured is returned for logging.
     */
    private static ProcessOutput runProcess(ProcessBuilder pb, long timeout, TimeUnit unit, Charset charset)
            throws IOException, InterruptedException {
        Process proc = pb.start();
        final String[] outputHolder = {""};
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try {
                outputHolder[0] = new String(proc.getInputStream().readAllBytes(), charset);
            } catch (IOException ignored) {
                // stream closed because the process was destroyed
            }
        });
        boolean finished;
        try {
            finished = proc.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            proc.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            proc.destroyForcibly();
        }
        try {
            outputReader.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ProcessOutput(finished ? proc.exitValue() : -1, outputHolder[0], !finished);
    }

    /** First line of Gradle/Maven tool output for logs (failure diagnosis). */
    private static String summarizeProcessOutput(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        for (String line : output.split("\r?\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("Downloading") && !t.startsWith(">")) {
                return t.length() > 200 ? t.substring(0, 200) + "…" : t;
            }
        }
        return output.trim().length() > 200 ? output.trim().substring(0, 200) + "…" : output.trim();
    }

    /** Static fallback: parse build.gradle declarations and resolve versions from BOM POMs. */
    private ParseResult parseGradleStatic(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps;
        try {
            comps = bomVersionResolver.parseGradleDeclaredWithBom(dir);
            log.info("[DependencyParser][Gradle] Static+BOM parsed {} components in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][Gradle] Failed to static-parse build.gradle: {}", e.getMessage());
            comps = List.of();
        }
        return new ParseResult("MAVEN", comps);
    }

    // Python: requirements.txt fallback (lock files are handled in parseDependencies)
    private ParseResult parsePython(Path dir, String repoName) {
        return new PythonManifestParser().parsePython(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parseRequirementsFile(Path reqFile, String repoName) {
        return new PythonManifestParser().parseRequirementsFile(reqFile, repoName);
    }

    /**
     * Parses {@code [project].dependencies} from {@code pyproject.toml} when no lock file is present.
     */
    private List<ScanPayload.ComponentPayload> parsePyprojectToml(Path tomlFile, String repoName) {
        return new PythonManifestParser().parsePyprojectToml(tomlFile, repoName);
    }

    private void addPythonRequirementLine(
            String rawLine, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        new PythonManifestParser().addPythonRequirementLine(rawLine, seen, comps);
    }

    private static String stripQuotes(String s) {
        return PythonManifestParser.stripQuotes(s);
    }

    /** Static Cargo.toml fallback when Cargo.lock is absent. */
    private List<ScanPayload.ComponentPayload> parseCargoToml(Path dir, String repoName) {
        return new CargoManifestParser().parseCargoToml(dir, repoName);
    }

    /** go.mod fallback when go.sum is absent. */
    private List<ScanPayload.ComponentPayload> parseGoModDeclared(Path dir, String repoName) {
        return new GoManifestParser().parseGoModDeclared(dir, repoName);
    }

    // ── Lock-file & new-ecosystem parsers ──────────────────────────────────

    /**
     * Generic parser for TOML-formatted lock files using {@code [[package]]} blocks
     * (poetry.lock, uv.lock, Cargo.lock).
     */
    private List<ScanPayload.ComponentPayload> parseTomlPackageLock(Path lockFile, String ecosystem, String repoName) {
        return new CargoManifestParser().parseTomlPackageLock(lockFile, ecosystem, repoName);
    }

    /**
     * Parses {@code Pipfile.lock} (JSON) — reads {@code default} and {@code develop} sections,
     * deduplicating across sections (a package in both lists is kept only once).
     */
    private List<ScanPayload.ComponentPayload> parsePipfileLock(Path dir, String repoName) {
        return new PythonManifestParser().parsePipfileLock(dir, repoName);
    }

    /**
     * Parses {@code go.sum} — each line: {@code <module> <version>[/go.mod] <hash>}.
     * De-duplicates by module path to get one entry per dependency.
     */
    private List<ScanPayload.ComponentPayload> parseGoSum(Path dir, String repoName) {
        return new GoManifestParser().parseGoSum(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parseNuGetLockFile(Path dir, String repoName) {
        return new NugetManifestParser().parseNuGetLockFile(dir, repoName);
    }

    /**
     * Runs {@code dotnet list package --include-transitive --format json} when the SDK is on PATH.
     * Returns {@code null} when dotnet is unavailable or the command fails.
     */
    private List<ScanPayload.ComponentPayload> runDotNetListPackages(Path dir, String repoName, ManifestIndex index) {
        if (!allowBuildExec) {
            log.warn("[DependencyParser][NuGet] Build execution is disabled "
                    + "(oswl.quick-import.allow-build-exec=false) — skipping dotnet list for '{}', using static parse", repoName);
            return null;
        }
        if (!isCommandOnPath("dotnet")) {
            log.debug("[DependencyParser][NuGet] dotnet not on PATH for '{}', skipping CLI resolve", repoName);
            return null;
        }
        Path target = findDotNetListTarget(index);
        if (target == null) {
            log.debug("[DependencyParser][NuGet] No .sln/.csproj for dotnet list in '{}'", repoName);
            return null;
        }
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String dotnetArgs = "dotnet list \"" + target + "\" package --include-transitive --format json";
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", dotnetArgs)
                    : List.of("dotnet", "list", target.toString(),
                            "package", "--include-transitive", "--format", "json");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            log.info("[DependencyParser][NuGet] Running dotnet list package for '{}' ({})", repoName, target.getFileName());
            ProcessOutput result = runProcess(pb, 5, TimeUnit.MINUTES, StandardCharsets.UTF_8);
            if (result.timedOut()) {
                log.warn("[DependencyParser][NuGet] dotnet list timed out for '{}' — partial output: {}",
                        repoName, summarizeProcessOutput(result.output()));
                return null;
            }
            if (result.exitCode() != 0) {
                log.warn("[DependencyParser][NuGet] dotnet list exited {} for '{}' — {}",
                        result.exitCode(), repoName, summarizeProcessOutput(result.output()));
                return null;
            }
            List<ScanPayload.ComponentPayload> comps = parseDotNetListJson(result.output(), repoName);
            if (comps.isEmpty()) {
                log.warn("[DependencyParser][NuGet] dotnet list produced no packages for '{}'", repoName);
                return null;
            }
            log.info("[DependencyParser][NuGet] dotnet list resolved {} components for '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][NuGet] dotnet list failed for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private Path findDotNetListTarget(ManifestIndex index) {
        List<Path> solutions = indexBySuffix(index, ".sln");
        if (!solutions.isEmpty()) {
            return solutions.stream()
                    .min(Comparator.comparingInt(p -> index.root().relativize(p).getNameCount()))
                    .orElse(solutions.getFirst());
        }
        List<Path> projects = indexBySuffix(index, ".csproj");
        if (projects.isEmpty()) {
            return null;
        }
        return projects.stream()
                .min(Comparator.comparingInt(p -> index.root().relativize(p).getNameCount()))
                .orElse(projects.getFirst());
    }

    private List<ScanPayload.ComponentPayload> parseDotNetListJson(String json, String repoName) {
        return new NugetManifestParser().parseDotNetListJson(json, repoName);
    }

    /** Standalone entry point (no shared index): builds a one-off index for {@code dir}. */
    private ParseResult parseNuGetStatic(Path dir, String repoName) {
        return new NugetManifestParser().parseNuGetStatic(dir, repoName);
    }

    private ParseResult parseNuGetStatic(Path dir, String repoName, ManifestIndex index) {
        return new NugetManifestParser().parseNuGetStatic(dir, repoName, index);
    }

    private Map<String, String> buildNuGetPropsVersionIndex(ManifestIndex index) {
        return new NugetManifestParser().buildNuGetPropsVersionIndex(index);
    }

    private void mergeDirectoryPackageVersions(
            DocumentBuilderFactory dbf, Path propsFile, Map<String, String> propsVersions) {
        new NugetManifestParser().mergeDirectoryPackageVersions(dbf, propsFile, propsVersions);
    }

    private void mergeNuGetCsproj(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps,
            Map<String, String> propsVersions) {
        new NugetManifestParser().mergeNuGetCsproj(doc, seen, comps, propsVersions);
    }

    private void mergeNuGetPackageConfig(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        new NugetManifestParser().mergeNuGetPackageConfig(doc, seen, comps);
    }

    private String resolveNuGetProperty(String raw, Map<String, String> propsVersions) {
        return new NugetManifestParser().resolveNuGetProperty(raw, propsVersions);
    }

    private static String firstNonBlank(String... values) {
        return NugetManifestParser.firstNonBlank(values);
    }

    private boolean isCommandOnPath(String command) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        try {
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", command + " --version")
                    : List.of(command, "--version");
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            return proc.waitFor(15, TimeUnit.SECONDS) && proc.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private List<ScanPayload.ComponentPayload> parseGemfileLock(Path dir, String repoName) {
        return new RubyLockParser().parseGemfileLock(dir, repoName);
    }

    /**
     * Parses {@code composer.lock} (JSON) — reads the {@code packages} (runtime) and
     * {@code packages-dev} (tagged scope=dev) arrays. Package names keep the Packagist
     * {@code vendor/package} form so the purl becomes {@code pkg:composer/<vendor>/<package>@<version>}.
     */
    private List<ScanPayload.ComponentPayload> parseComposerLock(Path dir, String repoName) {
        return new ComposerLockParser().parseComposerLock(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parseComposerLockJson(JsonNode root, String repoName) {
        return new ComposerLockParser().parseComposerLockJson(root, repoName);
    }

    private void addComposerPackages(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                                     JsonNode packages, String scope) {
        new ComposerLockParser().addComposerPackages(comps, seen, packages, scope);
    }

    /**
     * Reads a composer.lock package's {@code license} array (SPDX ids). deps.dev does not support
     * the COMPOSER system, so this manifest field is the only license source for PHP packages.
     */
    private static List<String> extractComposerLicenses(JsonNode pkg) {
        return ComposerLockParser.extractComposerLicenses(pkg);
    }

    /** Strips the optional leading "v" from a composer version (composer normalizes v1.2.3 → 1.2.3). */
    private static String normalizeComposerVersion(String version) {
        return ComposerLockParser.normalizeComposerVersion(version);
    }

    /**
     * Parses {@code conan.lock} (JSON) — Conan 2.x: flat {@code requires} /
     * {@code build_requires} (tagged scope=dev) ref arrays; Conan 1.x: {@code graph_lock.nodes[*].ref}.
     * Refs look like {@code name/version[@user/channel][#revision][%timestamp]}; only the
     * {@code name} and {@code version} parts are kept (OSV ecosystem "ConanCenter" uses plain names).
     */
    private List<ScanPayload.ComponentPayload> parseConanLock(Path dir, String repoName) {
        return new ConanLockParser().parseConanLock(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parseConanLockJson(JsonNode root, String repoName) {
        return new ConanLockParser().parseConanLockJson(root, repoName);
    }

    private void addConanRefs(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                              JsonNode refs, String scope) {
        new ConanLockParser().addConanRefs(comps, seen, refs, scope);
    }

    private void addConanRef(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                             String ref, String scope) {
        new ConanLockParser().addConanRef(comps, seen, ref, scope);
    }

    /**
     * Matches a top-level {@code PODS:} entry line in a {@code Podfile.lock}, e.g.
     * {@code "  - Alamofire (5.6.4)"} or {@code "  - GoogleUtilities/Environment (7.11.0):"}.
     * Exactly 2 spaces of indentation — 4-space lines are a pod's own sub-dependencies, not
     * separate locked entries, and are intentionally not matched.
     */


    /**
     * Parses {@code Podfile.lock}'s {@code PODS:} section. A subspec entry (e.g.
     * {@code "GoogleUtilities/AppDelegateSwizzler (7.11.0)"}) is folded into its base pod
     * ({@code GoogleUtilities}) — subspecs aren't separately published, so podspec resolution
     * only ever needs the base pod name.
     */
    private List<ScanPayload.ComponentPayload> parsePodfileLock(Path dir, String repoName) {
        return new CocoaPodsLockParser().parsePodfileLock(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parsePodfileLockLines(List<String> lines, String repoName) {
        return new CocoaPodsLockParser().parsePodfileLockLines(lines, repoName);
    }

    /**
     * Parses {@code conda-lock.yml}. Each entry's {@code manager} field says whether it was
     * installed via conda or pip: {@code pip} entries are already real PyPI names, and
     * {@code conda} entries are looked up in {@link CondaPypiMappingService} — a hit means the
     * package is a Python project repackaged for conda (gets real OSV PyPI coverage under the
     * mapped name); a miss almost always means a native (non-Python) library, tagged
     * {@code CONDA} and left for the caller's existing "no OSV mapping" guard to render as
     * {@code UNKNOWN} rather than a false "no vulnerabilities".
     */
    private List<ScanPayload.ComponentPayload> parseCondaLock(Path dir, String repoName) {
        return new CondaLockParser(condaPypiMappingService).parseCondaLock(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parseCondaLockYaml(Map<?, ?> root, String repoName) {
        return new CondaLockParser(condaPypiMappingService).parseCondaLockYaml(root, repoName);
    }

    /** SnakeYAML returns scalars as their inferred Java type (String/Integer/Double/Boolean) — normalize to String. */
    private static String yamlString(Object value) {
        return CondaLockParser.yamlString(value);
    }

    /**
     * Parses {@code pixi.lock}. Packages are grouped per environment and per platform; each
     * entry is either a {@code conda:} package URL (name/version derived from the filename,
     * then run through the same conda→PyPI mapping as conda-lock.yml) or a {@code pypi:}
     * entry carrying explicit {@code name}/{@code version} fields.
     */
    private List<ScanPayload.ComponentPayload> parsePixiLock(Path dir, String repoName) {
        return new CondaLockParser(condaPypiMappingService).parsePixiLock(dir, repoName);
    }

    private List<ScanPayload.ComponentPayload> parsePixiLockYaml(Map<?, ?> root, String repoName) {
        return new CondaLockParser(condaPypiMappingService).parsePixiLockYaml(root, repoName);
    }

    /**
     * Parses a {@code conda list --explicit} spec: comment headers, an {@code @EXPLICIT}
     * marker, then one conda package URL per line. Each URL's filename yields the pinned
     * name/version; the conda→PyPI mapping applies exactly as for conda-lock.yml.
     */
    private List<ScanPayload.ComponentPayload> parseCondaExplicitLines(List<String> lines, String repoName) {
        return new CondaLockParser(condaPypiMappingService).parseCondaExplicitLines(lines, repoName);
    }

    /**
     * Derives (name, version) from a conda package URL or filename, e.g.
     * {@code "…/linux-64/openssl-3.1.1-hd590300_1.conda"} → {@code ["openssl", "3.1.1"]}.
     * Filenames are {@code name-version-build}; names may contain '-' but versions and build
     * strings never do, so the split happens from the right.
     */
    private static String[] parseCondaPackageNameVersion(String urlOrFilename) {
        return CondaLockParser.parseCondaPackageNameVersion(urlOrFilename);
    }

    /**
     * Parses an uploaded raw lock file (composer.lock / conan.lock / Podfile.lock /
     * conda-lock.yml / pixi.lock / a `conda list --explicit` spec), detected by content shape.
     * Used by the SBOM-upload import path. Returns {@code null} when the content is not a
     * recognized lock file so the caller can fall back to CycloneDX SBOM parsing.
     */
    public List<ScanPayload.ComponentPayload> parseUploadedLockFile(byte[] content, String label) {
        return new UploadedLockFileParser(condaPypiMappingService).parseUploadedLockFile(content, label);
    }

    /** composer.lock shape: "packages" array plus a composer-only marker key. */
    private static boolean isComposerLockJson(JsonNode root) {
        return UploadedLockFileParser.isComposerLockJson(root);
    }

    /** conan.lock shape: Conan 1.x "graph_lock", or Conan 2.x flat "requires" array + "version". */
    private static boolean isConanLockJson(JsonNode root) {
        return UploadedLockFileParser.isConanLockJson(root);
    }

    // ── C/C++ manifest parsers ─────────────────────────────────────────────

    /**
     * Parses {@code vcpkg.json} manifest dependencies. Dependency entries may be plain names,
     * objects with a {@code name} and optional {@code version>=} constraint, or feature objects.
     * Only the declared name and best-effort version are emitted (vcpkg.json is not a lock file).
     */
    private List<ScanPayload.ComponentPayload> parseVcpkgJson(Path dir, String repoName) {
        return new NativeManifestParser().parseVcpkgJson(dir, repoName);
    }

    /**
     * Parses {@code vcpkg-configuration.json}. The file mainly describes registries, but it can
     * contain named registry packages with versions. Best-effort parse: any object with both
     * {@code name} and a version-ish field is emitted.
     */
    private List<ScanPayload.ComponentPayload> parseVcpkgConfigurationJson(Path dir, String repoName) {
        return new NativeManifestParser().parseVcpkgConfigurationJson(dir, repoName);
    }

    private void extractVcpkgRegistryPackages(JsonNode node, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        new NativeManifestParser().extractVcpkgRegistryPackages(node, seen, comps);
    }

    private void extractVcpkgRegistryPackage(JsonNode obj, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        new NativeManifestParser().extractVcpkgRegistryPackage(obj, seen, comps);
    }

    /**
     * Parses {@code .gitmodules}: for each submodule runs {@code git ls-tree HEAD <path>}
     * to capture the pinned commit SHA. Submodules whose SHA cannot be resolved are skipped.
     */
    private List<ScanPayload.ComponentPayload> parseGitSubmodules(Path dir, String repoName) {
        Path gitmodules = dir.resolve(".gitmodules");
        if (!Files.isRegularFile(gitmodules)) return List.of();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(gitmodules, StandardCharsets.UTF_8);
            String currentName = null;
            String currentPath = null;
            Pattern sectionP = Pattern.compile("^\\s*\\[submodule\\s+\"([^\"]+)\"\\s*]\\s*$");
            Pattern propP = Pattern.compile("^\\s*(\\w+)\\s*=\\s*(.+?)\\s*$");
            for (String raw : lines) {
                Matcher sm = sectionP.matcher(raw);
                if (sm.matches()) {
                    flushSubmodule(dir, repoName, currentName, currentPath, seen, comps);
                    currentName = sm.group(1);
                    currentPath = null;
                    continue;
                }
                Matcher pm = propP.matcher(raw);
                if (pm.matches() && currentName != null) {
                    String key = pm.group(1);
                    String val = pm.group(2);
                    if ("path".equals(key)) currentPath = val;
                }
            }
            flushSubmodule(dir, repoName, currentName, currentPath, seen, comps);
            log.info("[DependencyParser][GitSubmodule] Parsed {} components from .gitmodules in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][GitSubmodule] Failed to parse .gitmodules for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private void flushSubmodule(Path dir, String repoName, String name, String path,
                                Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        if (name == null || name.isBlank()) return;
        String sha = resolveSubmoduleSha(dir, path);
        if (sha == null || sha.isBlank()) {
            log.debug("[DependencyParser][GitSubmodule] Skipping '{}' in '{}' — no pinned SHA", name, repoName);
            return;
        }
        if (seen.add(name + ":" + sha)) {
            comps.add(buildComponent(name, sha, "SUBMODULE"));
        }
    }

    private String resolveSubmoduleSha(Path dir, String path) {
        if (path == null || path.isBlank()) return null;
        try {
            List<String> cmd = List.of("git", "ls-tree", "HEAD", path);
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
            ProcessOutput result = runProcess(pb, 30, TimeUnit.SECONDS, StandardCharsets.UTF_8);
            if (result.exitCode() != 0 || result.output().isBlank()) return null;
            // Output format: <mode> commit <sha> <tab> <path>
            for (String line : result.output().split("\\r?\\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3 && "commit".equals(parts[1])) {
                    return parts[2];
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("[DependencyParser][GitSubmodule] git ls-tree failed for '{}': {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code CMakeLists.txt} for {@code FetchContent_Declare} and {@code ExternalProject_Add}
     * declarations that pin a dependency via {@code GIT_REPOSITORY + GIT_TAG} or {@code URL}.
     * The dependency name is taken from the declaration identifier or the repository/archive basename.
     */
    private List<ScanPayload.ComponentPayload> parseCMakeLists(Path dir, String repoName) {
        return new NativeManifestParser().parseCMakeLists(dir, repoName);
    }

    private void parseCMakeCommand(String content, String command, Set<String> seen,
                                   List<ScanPayload.ComponentPayload> comps) {
        new NativeManifestParser().parseCMakeCommand(content, command, seen, comps);
    }

    /**
     * OS-package inventory from a Dockerfile's base image + explicitly
     * version-pinned {@code apt-get install}/{@code apk add} packages.
     * See {@link com.salkcoding.oswl.service.container.DockerfileParser} for scope.
     */
    private List<ScanPayload.ComponentPayload> parseDockerfile(Path dockerfile, String repoName) {
        try {
            String content = Files.readString(dockerfile, StandardCharsets.UTF_8);
            return new com.salkcoding.oswl.service.container.DockerfileParser().parse(content, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Dockerfile] Failed to parse Dockerfile for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private String extractCMakeQuotedArg(String body, String key) {
        return new NativeManifestParser().extractCMakeQuotedArg(body, key);
    }

    private String cmakeBasenameFromUrl(String url) {
        return new NativeManifestParser().cmakeBasenameFromUrl(url);
    }

    private String normalizeGitTag(String tag) {
        return new NativeManifestParser().normalizeGitTag(tag);
    }

    private String versionFromArchiveUrl(String url) {
        return new NativeManifestParser().versionFromArchiveUrl(url);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }

    /** Normalizes a Maven {@code <scope>} to the noise-cut tag; compile/runtime → null (production). */
    private static String normalizeMavenScope(String scope) {
        return MavenPomParser.normalizeMavenScope(scope);
    }

    /** Builds a scan payload from parse results (version label supplied by caller). */
    public ScanPayload buildScanPayload(ParseResult deps, String version) {
        return ScanPayload.create(version, deps.components());
    }
}
