package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

    /**
     * SECURITY: executing mvnw/gradlew/dotnet from a cloned repository runs arbitrary repo
     * code on this server. Disabled by default; when false the parser falls back to static
     * manifest parsing (no transitive resolution via build tools).
     */
    @Value("${oswl.quick-import.allow-build-exec:false}")
    private boolean allowBuildExec;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Console tools (mvnw, gradlew, npm) write human-readable output in the OS console codepage (e.g. MS949 on Korean Windows). */
    private static final Charset CONSOLE_CHARSET = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
    private static final Set<String> MANIFEST_SKIP_DIRS = ManifestCollectRules.SKIP_DIRS;

    /**
     * Every ecosystem tag this parser can emit on a {@code ComponentPayload}. Kept as an explicit
     * set (rather than derived from the literals scattered through the per-format parsers) so a
     * regression test can assert each tag is covered by the OSV ecosystem mapping — a tag missing
     * there would silently report "no vulnerabilities" for the whole ecosystem.
     */
    public static final Set<String> EMITTED_ECOSYSTEMS = Set.of(
            "MAVEN", "NPM", "PYPI", "GO", "CARGO", "NUGET", "RUBYGEMS", "COMPOSER",
            "CONAN", "VCPKG", "SUBMODULE", "VENDORED");

    public record ParseResult(String ecosystem, List<ScanPayload.ComponentPayload> components) {}

    private record GradleComponent(String name, String version, List<List<ScanPayload.DependencyNodeRef>> paths) {}

    /** Outcome of an external process run: exit code, captured merged output, timeout flag. */
    private record ProcessOutput(int exitCode, String output, boolean timedOut) {}

    public ParseResult parseDependencies(Path cloneDir, String repoName) {
        return parseDependencies(cloneDir, repoName, null);
    }

    /**
     * @param manifestProgress optional D3 callback receiving {@code (processedManifests, totalManifests)}
     *                         as each manifest group is handled — the A3 index knows the full
     *                         manifest count up front, so the caller can interpolate continuous
     *                         progress. Parsing must never fail because of reporting, so callback
     *                         exceptions are swallowed at debug level.
     */
    public ParseResult parseDependencies(Path cloneDir, String repoName,
                                         BiConsumer<Integer, Integer> manifestProgress) {
        List<ScanPayload.ComponentPayload> allComps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> ecosystems = new ArrayList<>();

        // A3: walk the tree exactly once; every manifest lookup below hits this index.
        ManifestIndex index = buildIndex(cloneDir);
        log.debug("[DependencyParser] Manifest index built for '{}': {} basenames, {} suffixes",
                repoName, index.byFileName().size(), index.bySuffix().size());

        // D3: progress denominator = every distinct indexed manifest; numerator = the ones
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
        Element c = getDirectChild(parent, tag);
        return c != null ? c.getTextContent().trim() : null;
    }

    /** Returns the first direct child element with the given tag name, or null. */
    private Element getDirectChild(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && tag.equals(e.getTagName())) return e;
        }
        return null;
    }

    /** Resolves ${prop} placeholders against the given property map. Returns unchanged if unresolved. */
    private String resolveProp(String raw, Map<String, String> props) {
        if (raw == null || !raw.contains("${")) return raw;
        Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = props.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Multi-manifest helpers ─────────────────────────────────────────────

    /** Parses a single {@code pom.xml}'s direct dependencies, tagging non-runtime scope (test/provided/system). */
    private List<ScanPayload.ComponentPayload> parseSingleMavenPom(Path pomFile, Path projectDir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();
            Element project = doc.getDocumentElement();
            Map<String, String> props = new HashMap<>();
            String projectVersion = getDirectChildText(project, "version");
            Element parent = getDirectChild(project, "parent");
            if (parent != null) {
                String parentVersion = getDirectChildText(parent, "version");
                if (projectVersion == null) projectVersion = parentVersion;
                String parentGroup = getDirectChildText(parent, "groupId");
                if (parentGroup != null) props.put("project.parent.groupId", parentGroup);
                if (parentVersion != null) props.put("project.parent.version", parentVersion);
            }
            if (projectVersion != null) {
                props.put("project.version", projectVersion);
                props.put("version", projectVersion);
                props.put("pom.version", projectVersion);
            }
            Element properties = getDirectChild(project, "properties");
            if (properties != null) {
                NodeList children = properties.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element pe) {
                        props.put(pe.getTagName(), pe.getTextContent().trim());
                    }
                }
            }
            Element depsRoot = getDirectChild(project, "dependencies");
            if (depsRoot == null) return comps;
            NodeList deps = depsRoot.getChildNodes();
            for (int i = 0; i < deps.getLength(); i++) {
                if (!(deps.item(i) instanceof Element dep) || !"dependency".equals(dep.getTagName())) continue;
                String groupId    = resolveProp(getDirectChildText(dep, "groupId"), props);
                String artifactId = resolveProp(getDirectChildText(dep, "artifactId"), props);
                String version    = resolveProp(getDirectChildText(dep, "version"), props);
                String scope      = getDirectChildText(dep, "scope");
                if (groupId == null || artifactId == null) continue;
                // Non-runtime scopes are tagged (not dropped) so the UI can badge and default-filter them.
                comps.add(buildComponent(groupId + ":" + artifactId, version, "MAVEN")
                        .withScope(normalizeMavenScope(scope)));
            }
            log.debug("[DependencyParser][Maven] Parsed {} deps from {}", comps.size(), pomFile);
            comps = bomVersionResolver.enrichComponentVersions(projectDir, comps);
        } catch (Exception e) {
            log.warn("[DependencyParser][Maven] Failed to parse {}: {}", pomFile, e.getMessage());
        }
        return comps;
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
    private record ManifestIndex(Path root,
                                 Map<String, List<Path>> byFileName,
                                 Map<String, List<Path>> bySuffix) {}

    /**
     * Walks the tree under {@code root} exactly once and indexes every file whose
     * basename is in {@link ManifestCollectRules#EXACT_FILE_NAMES} or ends with one of
     * {@link ManifestCollectRules#FILE_SUFFIXES}, skipping any path segment listed in
     * {@link ManifestCollectRules#SKIP_DIRS}. Index lists are sorted by path string so
     * iteration order is deterministic.
     */
    private ManifestIndex buildIndex(Path root) {
        Map<String, List<Path>> byFileName = new HashMap<>();
        Map<String, List<Path>> bySuffix = new HashMap<>();
        final Path rootReal;
        try {
            rootReal = new CloneRootPathGuard(root).root();
        } catch (IOException e) {
            log.warn("[DependencyParser] buildIndex: invalid clone root '{}': {}", root, e.getMessage());
            return new ManifestIndex(root, byFileName, bySuffix);
        }
        try {
            Files.walkFileTree(rootReal, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                    if (!dir.equals(rootReal)) {
                        Path rel = rootReal.relativize(dir);
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (MANIFEST_SKIP_DIRS.contains(rel.getName(i).toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    try {
                        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!fileReal.startsWith(rootReal)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = rootReal.relativize(fileReal);
                        for (int i = 0; i < rel.getNameCount() - 1; i++) {
                            if (MANIFEST_SKIP_DIRS.contains(rel.getName(i).toString())) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        String name = fileReal.getFileName().toString();
                        if (ManifestCollectRules.EXACT_FILE_NAMES.contains(name)) {
                            byFileName.computeIfAbsent(name, k -> new ArrayList<>()).add(fileReal);
                        }
                        for (String suffix : ManifestCollectRules.FILE_SUFFIXES) {
                            if (name.endsWith(suffix)) {
                                bySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(fileReal);
                            }
                        }
                    } catch (IOException e) {
                        log.debug("[DependencyParser] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[DependencyParser] buildIndex error under '{}': {}", root, e.getMessage());
        }
        Comparator<Path> byPath = Comparator.comparing(Path::toString);
        byFileName.values().forEach(paths -> paths.sort(byPath));
        bySuffix.values().forEach(paths -> paths.sort(byPath));
        return new ManifestIndex(rootReal, byFileName, bySuffix);
    }

    /** Returns indexed paths for the given exact basenames, merged and sorted by path string. */
    private List<Path> indexByNames(ManifestIndex index, String... fileNames) {
        List<Path> result = new ArrayList<>();
        for (String name : fileNames) {
            result.addAll(index.byFileName().getOrDefault(name, List.of()));
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    /** Returns indexed paths whose basename ends with {@code suffix} (already path-sorted). */
    private List<Path> indexBySuffix(ManifestIndex index, String suffix) {
        return index.bySuffix().getOrDefault(suffix, List.of());
    }

    /** Mirrors the legacy {@code Files.walk(dir, 8)} depth limit on top of the shared index. */
    private boolean hasCsprojFiles(ManifestIndex index) {
        for (Path p : indexBySuffix(index, ".csproj")) {
            if (index.root().relativize(p).getNameCount() <= 8) {
                return true;
            }
        }
        return false;
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
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        List<Path> catalogs = new ArrayList<>();
        // Original logic used Files.walk(dir, 3); keep that depth limit on top of the shared index.
        for (Path p : indexBySuffix(index, ".versions.toml")) {
            if (index.root().relativize(p).getNameCount() <= 3) {
                catalogs.add(p);
            }
        }
        if (catalogs.isEmpty()) { log.debug("[DependencyParser][Gradle] No *.versions.toml in '{}'", repoName); return comps; }
        for (Path catalog : catalogs) {
            try {
                List<String> lines = Files.readAllLines(catalog, StandardCharsets.UTF_8);
                Map<String, String> versions = new LinkedHashMap<>();
                boolean inVersions = false, inLibraries = false;
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.equals("[versions]"))  { inVersions = true;  inLibraries = false; continue; }
                    if (line.equals("[libraries]")) { inLibraries = true; inVersions = false;  continue; }
                    if (line.startsWith("["))       { inVersions = false; inLibraries = false; continue; }
                    if (inVersions) {
                        Matcher vm = Pattern.compile("^([\\w.\\-]+)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line);
                        if (vm.find()) versions.put(vm.group(1), vm.group(2));
                    } else if (inLibraries) {
                        // Short form: alias = "group:artifact:version"
                        Matcher shortM = Pattern.compile(
                                "^[\\w.\\-]+\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+):([\\w.+\\-]+)[\"']").matcher(line);
                        if (shortM.find()) { comps.add(buildComponent(shortM.group(1), shortM.group(2), "MAVEN")); continue; }
                        // Table form: alias = { module = "g:a", version.ref = "key" | version = "x" }
                        Matcher tableM = Pattern.compile("^[\\w.\\-]+\\s*=\\s*\\{(.+)\\}").matcher(line);
                        if (tableM.find()) {
                            String body = tableM.group(1);
                            Matcher modM = Pattern.compile("module\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+)[\"']").matcher(body);
                            if (modM.find()) {
                                String module = modM.group(1); String version = null;
                                Matcher vRefM = Pattern.compile("version\\.ref\\s*=\\s*[\"']([\\w.\\-]+)[\"']").matcher(body);
                                Matcher vM    = Pattern.compile("(?<![.\\w])version\\s*=\\s*[\"']([\\w.+\\-]+)[\"']").matcher(body);
                                if (vRefM.find()) version = versions.get(vRefM.group(1));
                                else if (vM.find()) version = vM.group(1);
                                if (version != null && !version.isBlank()) comps.add(buildComponent(module, version, "MAVEN"));
                            }
                        }
                    }
                }
                log.info("[DependencyParser][Gradle] Version catalog {} → {} entries", catalog.getFileName(), comps.size());
            } catch (Exception e) {
                log.warn("[DependencyParser][Gradle] Failed to parse {}: {}", catalog, e.getMessage());
            }
        }
        return comps;
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
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package-lock.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            if (root.has("packages")) {
                // lockfileVersion 2/3 (npm 7+): "packages" → { "node_modules/x": { version, ... } }
                root.path("packages").properties().forEach(e -> {
                    String pkgPath = e.getKey();
                    if (pkgPath.isEmpty()) return; // skip root entry
                    String name = pkgPath.startsWith("node_modules/")
                            ? pkgPath.substring("node_modules/".length())
                            : pkgPath;
                    String version = e.getValue().path("version").asText(null);
                    if (name.isBlank() || version == null || version.isBlank()) return;
                    if (seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                });
            } else if (root.has("dependencies")) {
                // lockfileVersion 1 (npm 5/6): flat + nested "dependencies" tree
                Deque<Map.Entry<String, JsonNode>> queue = new ArrayDeque<>(root.path("dependencies").properties());
                while (!queue.isEmpty()) {
                    Map.Entry<String, JsonNode> entry = queue.poll();
                    String name    = entry.getKey();
                    JsonNode val   = entry.getValue();
                    String version = val.path("version").asText(null);
                    if (version != null && !version.isBlank() && seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                    if (val.has("dependencies")) {
                        queue.addAll(val.path("dependencies").properties());
                    }
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from package-lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse package-lock.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /** Fallback: parse package.json declared deps only (no transitive). */
    private ParseResult parseNpmPackageJson(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package.json").toFile());
            JsonNode deps    = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");

            addNpmDeps(comps, deps, null);
            addNpmDeps(comps, devDeps, "dev");
            log.info("[DependencyParser][npm] Parsed {} components from package.json in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][npm] Failed to parse package.json: {}", e.getMessage());
        }
        return new ParseResult("NPM", comps);
    }

    private void addNpmDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode, String scope) {
        if (depsNode == null || depsNode.isMissingNode()) return;
        depsNode.properties().forEach(entry -> {
            String name    = entry.getKey();
            String version = entry.getValue().asText().replaceAll("^[~^>=<]+ *", "");
            comps.add(buildComponent(name, version, "NPM").withScope(scope));
        });
    }

    /**
     * Parses {@code yarn.lock} (classic / v1 format). Each entry header may list multiple
     * descriptor strings (e.g. {@code "@scope/pkg@^1.0", "@scope/pkg@~1.1":}) followed by
     * an indented body containing {@code version "X.Y.Z"}. Same package may appear under
     * multiple resolved versions — we keep all distinct (name, version) pairs.
     */
    private List<ScanPayload.ComponentPayload> parseYarnLock(Path dir, String repoName) {
        try {
            List<String> lines = Files.readAllLines(dir.resolve("yarn.lock"), StandardCharsets.UTF_8);
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            List<String> pendingNames = new ArrayList<>();
            for (String raw : lines) {
                if (raw.isEmpty() || raw.startsWith("#")) continue;
                // Header: starts at column 0 and ends with ':'
                if (!Character.isWhitespace(raw.charAt(0)) && raw.endsWith(":")) {
                    pendingNames.clear();
                    String header = raw.substring(0, raw.length() - 1);
                    for (String desc : header.split(",")) {
                        String d = desc.trim();
                        if (d.startsWith("\"") && d.endsWith("\"")) d = d.substring(1, d.length() - 1);
                        // Strip @version: name is everything before the last '@' (but keep leading '@' for scoped)
                        int at = d.lastIndexOf('@');
                        if (at <= 0) continue;
                        pendingNames.add(d.substring(0, at));
                    }
                } else if (!pendingNames.isEmpty() && raw.startsWith("  version")) {
                    Matcher m = Pattern.compile("version[:\\s]+\"?([^\"\\s]+)\"?").matcher(raw);
                    if (m.find()) {
                        String version = m.group(1);
                        for (String name : pendingNames) {
                            if (seen.add(name + ":" + version)) {
                                comps.add(buildComponent(name, version, "NPM"));
                            }
                        }
                    }
                    pendingNames.clear();
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from yarn.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse yarn.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code pnpm-lock.yaml}. Supports v6+ (where {@code packages:} keys look like
     * {@code /name@version} or {@code 'name@version'}) and older v5 ({@code /name/version}).
     */
    private List<ScanPayload.ComponentPayload> parsePnpmLock(Path dir, String repoName) {
        try {
            List<String> lines = Files.readAllLines(dir.resolve("pnpm-lock.yaml"), StandardCharsets.UTF_8);
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            boolean inSection = false;
            // Modern pnpm v6+: '@scope/pkg@1.2.3':  or  /@scope/pkg@1.2.3:
            // Older pnpm v5  : /@scope/pkg/1.2.3:   or  /pkg/1.2.3:
            Pattern modern = Pattern.compile("^\\s{2}'?/?((?:@[^@/'\\s]+/)?[^@/'\\s]+)@([^()'\\s]+?)(?:\\([^)]+\\))?'?:\\s*$");
            Pattern legacy = Pattern.compile("^\\s{2}/((?:@[^/]+/)?[^/]+)/([0-9][^/_'\\s]+)(?:_[^:'\\s]*)?:\\s*$");
            for (String line : lines) {
                if (line.startsWith("packages:") || line.startsWith("snapshots:")) { inSection = true; continue; }
                if (inSection && !line.isEmpty() && !Character.isWhitespace(line.charAt(0))) { inSection = false; }
                if (!inSection) continue;
                Matcher m = modern.matcher(line);
                if (!m.matches()) m = legacy.matcher(line);
                if (m.matches()) {
                    String name = m.group(1);
                    String version = m.group(2);
                    if (seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from pnpm-lock.yaml in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse pnpm-lock.yaml for '{}': {}", repoName, e.getMessage());
            return null;
        }
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
        List<ScanPayload.ComponentPayload> comps = parseRequirementsFile(dir.resolve("requirements.txt"), repoName);
        return new ParseResult("PYPI", comps != null ? comps : List.of());
    }

    private List<ScanPayload.ComponentPayload> parseRequirementsFile(Path reqFile, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            if (!Files.isRegularFile(reqFile)) {
                return List.of();
            }
            for (String rawLine : Files.readAllLines(reqFile, StandardCharsets.UTF_8)) {
                addPythonRequirementLine(rawLine, seen, comps);
            }
            log.info("[DependencyParser][Python] Parsed {} components from {} in '{}'",
                    comps.size(), reqFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse {}: {}", reqFile, e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code [project].dependencies} from {@code pyproject.toml} when no lock file is present.
     */
    private List<ScanPayload.ComponentPayload> parsePyprojectToml(Path tomlFile, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(tomlFile, StandardCharsets.UTF_8);
            boolean inProject = false;
            boolean inDepsArray = false;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inProject = line.equals("[project]");
                    inDepsArray = false;
                    continue;
                }
                if (!inProject) {
                    continue;
                }
                if (line.startsWith("dependencies") && line.contains("[")) {
                    inDepsArray = true;
                    int open = line.indexOf('[');
                    int close = line.indexOf(']');
                    if (close > open) {
                        String inline = line.substring(open + 1, close).trim();
                        if (!inline.isBlank()) {
                            for (String part : inline.split(",")) {
                                addPythonRequirementLine(stripQuotes(part.trim()), seen, comps);
                            }
                        }
                        inDepsArray = !line.endsWith("]");
                    }
                    continue;
                }
                if (inDepsArray) {
                    if (line.equals("]")) {
                        inDepsArray = false;
                        continue;
                    }
                    String dep = line;
                    if (dep.endsWith(",")) {
                        dep = dep.substring(0, dep.length() - 1).trim();
                    }
                    if (dep.equals("]")) {
                        inDepsArray = false;
                        continue;
                    }
                    addPythonRequirementLine(stripQuotes(dep), seen, comps);
                }
            }
            log.info("[DependencyParser][Python] Parsed {} components from {} in '{}'",
                    comps.size(), tomlFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse pyproject.toml {}: {}", tomlFile, e.getMessage());
            return null;
        }
    }

    private void addPythonRequirementLine(
            String rawLine, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
            return;
        }
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash).trim();
        }
        int semi = line.indexOf(';');
        if (semi >= 0) {
            line = line.substring(0, semi).trim();
        }
        if (line.isEmpty()) {
            return;
        }
        Pattern fullForm = Pattern.compile(
                "^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*(===|==|>=|<=|~=|!=|<|>)\\s*([0-9][^\\s,;#]*)");
        Pattern bareName = Pattern.compile("^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*$");
        String name = null;
        String version = null;
        Matcher m = fullForm.matcher(line);
        if (m.find()) {
            name = m.group(1);
            version = m.group(3);
        } else {
            Matcher b = bareName.matcher(line);
            if (b.matches()) {
                name = b.group(1);
            }
        }
        if (name == null || name.isBlank()) {
            return;
        }
        if (seen.add(name + ":" + (version != null ? version : ""))) {
            comps.add(buildComponent(name, version, "PYPI"));
        }
    }

    private static String stripQuotes(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Static Cargo.toml fallback when Cargo.lock is absent. */
    private List<ScanPayload.ComponentPayload> parseCargoToml(Path dir, String repoName) {
        // Static Cargo.toml — handles inline string form and table form:
        //   foo = "1.0"
        //   foo = { version = "1.0", features = [...] }
        //   foo = { git = "..." }   (no version → skip)
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("Cargo.toml"), StandardCharsets.UTF_8);
            boolean inDeps = false;
            // Inline-string form: foo = "1.2.3-rc1+build"
            Pattern inlineP = Pattern.compile("^([\\w\\-]+)\\s*=\\s*[\"']([\\w.+\\-]+)[\"']\\s*$");
            // Table form: foo = { version = "1.2.3", ... }
            Pattern tableP  = Pattern.compile("^([\\w\\-]+)\\s*=\\s*\\{[^}]*\\bversion\\s*=\\s*[\"']([\\w.+\\-]+)[\"']");
            // Sub-table header: [dependencies.foo]
            Pattern subHeaderP = Pattern.compile("^\\[(?:dev-|build-)?dependencies\\.([\\w\\-]+)]");
            String pendingSubName = null;
            Pattern verLineP = Pattern.compile("^version\\s*=\\s*[\"']([\\w.+\\-]+)[\"']");
            for (String rawLine : lines) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Section headers
                if (trimmed.startsWith("[")) {
                    if (trimmed.equals("[dependencies]") || trimmed.equals("[dev-dependencies]")
                            || trimmed.equals("[build-dependencies]")) {
                        inDeps = true;
                        pendingSubName = null;
                        continue;
                    }
                    Matcher sh = subHeaderP.matcher(trimmed);
                    if (sh.find()) {
                        pendingSubName = sh.group(1);
                        inDeps = false;
                        continue;
                    }
                    inDeps = false;
                    pendingSubName = null;
                    continue;
                }
                if (inDeps) {
                    Matcher t = tableP.matcher(trimmed);
                    if (t.find()) {
                        if (seen.add(t.group(1))) comps.add(buildComponent(t.group(1), t.group(2), "CARGO"));
                        continue;
                    }
                    Matcher i = inlineP.matcher(trimmed);
                    if (i.find()) {
                        if (seen.add(i.group(1))) comps.add(buildComponent(i.group(1), i.group(2), "CARGO"));
                    }
                } else if (pendingSubName != null) {
                    Matcher v = verLineP.matcher(trimmed);
                    if (v.find()) {
                        if (seen.add(pendingSubName)) comps.add(buildComponent(pendingSubName, v.group(1), "CARGO"));
                        pendingSubName = null;
                    }
                }
            }
            log.info("[DependencyParser][Cargo] Parsed {} components from Cargo.toml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][Cargo] Failed to parse Cargo.toml: {}", e.getMessage());
        }
        return comps;
    }

    /** go.mod fallback when go.sum is absent. */
    private List<ScanPayload.ComponentPayload> parseGoModDeclared(Path dir, String repoName) {
        // go.mod fallback — we trim each line first, so both block and single patterns
        // match against the trimmed text.
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("go.mod"), StandardCharsets.UTF_8);
            boolean inRequire = false;
            // "require module v1.2.3" (single) and inside a require( … ) block "module v1.2.3"
            Pattern single = Pattern.compile("^require\\s+(\\S+)\\s+(v\\S+)");
            Pattern entry  = Pattern.compile("^(\\S+)\\s+(v\\S+)");
            for (String rawLine : lines) {
                // Strip end-of-line comments (e.g. "// indirect") before parsing.
                String t = rawLine;
                int idx = t.indexOf("//");
                if (idx >= 0) t = t.substring(0, idx);
                t = t.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("require (")) { inRequire = true; continue; }
                if (inRequire && t.equals(")"))                         { inRequire = false; continue; }
                Matcher m = inRequire ? entry.matcher(t) : single.matcher(t);
                if (m.find() && seen.add(m.group(1))) {
                    comps.add(buildComponent(m.group(1), m.group(2), "GO"));
                }
            }
            log.info("[DependencyParser][Go] Parsed {} components from go.mod in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][Go] Failed to parse go.mod: {}", e.getMessage());
        }
        return comps;
    }

    // ── Lock-file & new-ecosystem parsers ──────────────────────────────────

    /**
     * Generic parser for TOML-formatted lock files using {@code [[package]]} blocks
     * (poetry.lock, uv.lock, Cargo.lock).
     */
    private List<ScanPayload.ComponentPayload> parseTomlPackageLock(Path lockFile, String ecosystem, String repoName) {
        try {
            String content = Files.readString(lockFile, StandardCharsets.UTF_8);
            String[] blocks = content.split("\\[\\[package\\]\\]");
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Pattern nameP = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
            Pattern verP  = Pattern.compile("\\bversion\\s*=\\s*\"([^\"]+)\"");
            for (int i = 1; i < blocks.length; i++) {
                Matcher nm = nameP.matcher(blocks[i]);
                Matcher vm = verP.matcher(blocks[i]);
                if (nm.find() && vm.find()) {
                    comps.add(buildComponent(nm.group(1), vm.group(1), ecosystem));
                }
            }
            log.info("[DependencyParser][{}] Parsed {} components from {} in '{}'",
                    ecosystem, comps.size(), lockFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser] Failed to parse {}: {}", lockFile.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code Pipfile.lock} (JSON) — reads {@code default} and {@code develop} sections,
     * deduplicating across sections (a package in both lists is kept only once).
     */
    private List<ScanPayload.ComponentPayload> parsePipfileLock(Path dir, String repoName) {
        try {
            JsonNode root = new ObjectMapper().readTree(dir.resolve("Pipfile.lock").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String section : new String[]{"default", "develop"}) {
                JsonNode deps = root.path(section);
                if (deps.isMissingNode()) continue;
                String scope = "develop".equals(section) ? "dev" : null;
                deps.properties().forEach(e -> {
                    String name = e.getKey();
                    String ver  = e.getValue().path("version").asText("").replaceAll("^==", "");
                    if (!name.isBlank() && !ver.isBlank() && seen.add(name + ":" + ver)) {
                        comps.add(buildComponent(name, ver, "PYPI").withScope(scope));
                    }
                });
            }
            log.info("[DependencyParser][Python] Parsed {} components from Pipfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse Pipfile.lock: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code go.sum} — each line: {@code <module> <version>[/go.mod] <hash>}.
     * De-duplicates by module path to get one entry per dependency.
     */
    private List<ScanPayload.ComponentPayload> parseGoSum(Path dir, String repoName) {
        try {
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Pattern p = Pattern.compile("^(\\S+)\\s+(v[^\\s/]+)(?:/go\\.mod)?\\s");
            for (String line : Files.readAllLines(dir.resolve("go.sum"), StandardCharsets.UTF_8)) {
                Matcher m = p.matcher(line);
                if (m.find() && seen.add(m.group(1))) {
                    comps.add(buildComponent(m.group(1), m.group(2), "GO"));
                }
            }
            log.info("[DependencyParser][Go] Parsed {} components from go.sum in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Go] Failed to parse go.sum: {}", e.getMessage());
            return null;
        }
    }

    private List<ScanPayload.ComponentPayload> parseNuGetLockFile(Path dir, String repoName) {
        try {
            JsonNode root = new ObjectMapper().readTree(dir.resolve("packages.lock.json").toFile());
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            JsonNode deps = root.path("dependencies");
            if (!deps.isMissingNode()) {
                deps.properties().forEach(fw ->
                    fw.getValue().properties().forEach(pkg -> {
                        String name = pkg.getKey();
                        String ver  = pkg.getValue().path("resolved").asText(null);
                        if (name != null && ver != null && !ver.isBlank() && seen.add(name)) {
                            comps.add(buildComponent(name, ver, "NUGET"));
                        }
                    })
                );
            }
            log.info("[DependencyParser][NuGet] Parsed {} components from packages.lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][NuGet] Failed to parse packages.lock.json: {}", e.getMessage());
            return null;
        }
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
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            for (JsonNode project : root.path("projects")) {
                for (JsonNode fw : project.path("frameworks")) {
                    for (String section : new String[]{"topLevelPackages", "transitivePackages"}) {
                        for (JsonNode pkg : fw.path(section)) {
                            String id = pkg.path("id").asText(null);
                            String ver = pkg.path("resolvedVersion").asText(null);
                            if (id == null || id.isBlank() || ver == null || ver.isBlank()) {
                                continue;
                            }
                            if (seen.add(id + ":" + ver)) {
                                comps.add(buildComponent(id, ver, "NUGET"));
                            }
                        }
                    }
                }
            }
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][NuGet] Failed to parse dotnet list JSON for '{}': {}", repoName, e.getMessage());
            return List.of();
        }
    }

    /** Standalone entry point (no shared index): builds a one-off index for {@code dir}. */
    private ParseResult parseNuGetStatic(Path dir, String repoName) {
        return parseNuGetStatic(dir, repoName, buildIndex(dir));
    }

    private ParseResult parseNuGetStatic(Path dir, String repoName, ManifestIndex index) {
        Set<String> seen = new LinkedHashSet<>();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Map<String, String> propsVersions = buildNuGetPropsVersionIndex(index);
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            for (Path props : indexByNames(index, "Directory.Packages.props")) {
                mergeDirectoryPackageVersions(dbf, props, propsVersions);
            }
            List<Path> targets = new ArrayList<>();
            targets.addAll(indexBySuffix(index, ".csproj"));
            targets.addAll(indexByNames(index, "packages.config"));
            for (Path f : targets) {
                try {
                    Document doc = dbf.newDocumentBuilder().parse(f.toFile());
                    if (f.getFileName().toString().equals("packages.config")) {
                        mergeNuGetPackageConfig(doc, seen, comps);
                    } else {
                        mergeNuGetCsproj(doc, seen, comps, propsVersions);
                    }
                } catch (Exception ignored) {}
            }
            log.info("[DependencyParser][NuGet] Parsed {} components from {} manifest file(s) in '{}'",
                    comps.size(), targets.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][NuGet] Failed to parse .csproj/packages.config: {}", e.getMessage());
        }
        return new ParseResult("NUGET", comps);
    }

    private Map<String, String> buildNuGetPropsVersionIndex(ManifestIndex index) {
        Map<String, String> propsIndex = new LinkedHashMap<>();
        Pattern propVersion = Pattern.compile("<([\\w.]+)>\\s*([\\d][^<]*)\\s*</\\1>");
        for (Path props : indexBySuffix(index, ".props")) {
            try {
                String content = Files.readString(props, StandardCharsets.UTF_8);
                Matcher m = propVersion.matcher(content);
                while (m.find()) {
                    String key = m.group(1);
                    String val = m.group(2).trim();
                    if (!val.isBlank() && !val.contains("$(")) {
                        propsIndex.putIfAbsent(key, val);
                    }
                }
            } catch (Exception e) {
                log.debug("[DependencyParser][NuGet] props scan failed for {}: {}", props, e.getMessage());
            }
        }
        return propsIndex;
    }

    private void mergeDirectoryPackageVersions(
            DocumentBuilderFactory dbf, Path propsFile, Map<String, String> propsVersions) {
        try {
            Document doc = dbf.newDocumentBuilder().parse(propsFile.toFile());
            NodeList versions = doc.getElementsByTagName("PackageVersion");
            for (int i = 0; i < versions.getLength(); i++) {
                Element el = (Element) versions.item(i);
                String id = el.getAttribute("Include");
                String ver = el.getAttribute("Version");
                if (!id.isBlank() && !ver.isBlank()) {
                    propsVersions.put(id, resolveNuGetProperty(ver, propsVersions));
                }
            }
        } catch (Exception e) {
            log.debug("[DependencyParser][NuGet] Directory.Packages.props parse failed: {}", e.getMessage());
        }
    }

    private void mergeNuGetCsproj(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps,
            Map<String, String> propsVersions) {
        NodeList refs = doc.getElementsByTagName("PackageReference");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            if ("Remove".equalsIgnoreCase(ref.getAttribute("Update"))) {
                continue;
            }
            String name = firstNonBlank(ref.getAttribute("Include"), ref.getAttribute("Update"));
            if (name.isBlank() || name.contains("@(")) {
                continue;
            }
            String ver = firstNonBlank(
                    ref.getAttribute("Version"),
                    getDirectChildText(ref, "Version"));
            ver = resolveNuGetProperty(ver, propsVersions);
            if (propsVersions.containsKey(name) && (ver == null || ver.isBlank() || ver.startsWith("$("))) {
                ver = propsVersions.get(name);
            }
            String key = name + ":" + (ver != null ? ver : "");
            if (seen.add(key)) {
                comps.add(buildComponent(name, ver == null || ver.isBlank() ? null : ver, "NUGET"));
            }
        }
    }

    private void mergeNuGetPackageConfig(
            Document doc, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        NodeList refs = doc.getElementsByTagName("package");
        for (int i = 0; i < refs.getLength(); i++) {
            Element ref = (Element) refs.item(i);
            String name = firstNonBlank(ref.getAttribute("id"), ref.getAttribute("Include"));
            String ver = firstNonBlank(ref.getAttribute("version"), ref.getAttribute("Version"));
            if (!name.isBlank() && seen.add(name + ":" + ver)) {
                comps.add(buildComponent(name, ver.isBlank() ? null : ver, "NUGET"));
            }
        }
    }

    private String resolveNuGetProperty(String raw, Map<String, String> propsVersions) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        Matcher m = Pattern.compile("\\$\\(([^)]+)\\)").matcher(raw.trim());
        if (!m.matches()) {
            return raw.trim();
        }
        String resolved = propsVersions.get(m.group(1));
        return resolved != null ? resolved : raw.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
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
        try {
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            boolean inSpecs = false;
            Pattern specLine = Pattern.compile("^ {4}([A-Za-z0-9_.\\-]+)\\s+\\(([^)]+)\\)");
            for (String line : Files.readAllLines(dir.resolve("Gemfile.lock"), StandardCharsets.UTF_8)) {
                if (line.equals("  specs:")) { inSpecs = true; continue; }
                if (inSpecs && !line.startsWith(" ") && !line.isEmpty()) { inSpecs = false; continue; }
                if (!inSpecs) {
                    continue;
                }
                Matcher m = specLine.matcher(line);
                if (!m.find()) {
                    continue;
                }
                String name = m.group(1);
                String version = m.group(2).trim();
                if (version.startsWith(">=") || version.startsWith(">")
                        || version.startsWith("<") || version.startsWith("~>")
                        || version.startsWith("!=") || version.startsWith("=")) {
                    continue;
                }
                if (!version.matches("[0-9].*")) {
                    continue;
                }
                if (seen.add(name + ":" + version)) {
                    comps.add(buildComponent(name, version, "RUBYGEMS"));
                }
            }
            log.info("[DependencyParser][Ruby] Parsed {} components from Gemfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Ruby] Failed to parse Gemfile.lock: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code composer.lock} (JSON) — reads the {@code packages} (runtime) and
     * {@code packages-dev} (tagged scope=dev) arrays. Package names keep the Packagist
     * {@code vendor/package} form so the purl becomes {@code pkg:composer/<vendor>/<package>@<version>}.
     */
    private List<ScanPayload.ComponentPayload> parseComposerLock(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("composer.lock").toFile());
            return parseComposerLockJson(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Composer] Failed to parse composer.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private List<ScanPayload.ComponentPayload> parseComposerLockJson(JsonNode root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addComposerPackages(comps, seen, root.path("packages"), null);
        addComposerPackages(comps, seen, root.path("packages-dev"), "dev");
        log.info("[DependencyParser][Composer] Parsed {} components from composer.lock in '{}'", comps.size(), repoName);
        return comps;
    }

    private void addComposerPackages(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                                     JsonNode packages, String scope) {
        if (packages == null || !packages.isArray()) {
            return;
        }
        for (JsonNode pkg : packages) {
            String name    = pkg.path("name").asText(null);
            String version = normalizeComposerVersion(pkg.path("version").asText(null));
            if (name == null || name.isBlank() || version == null || version.isBlank()) {
                continue;
            }
            if (seen.add(name + ":" + version)) {
                comps.add(buildComponent(name, version, "COMPOSER")
                        .withScope(scope)
                        .withLicenses(extractComposerLicenses(pkg)));
            }
        }
    }

    /**
     * Reads a composer.lock package's {@code license} array (SPDX ids). deps.dev does not support
     * the COMPOSER system, so this manifest field is the only license source for PHP packages.
     */
    private static List<String> extractComposerLicenses(JsonNode pkg) {
        JsonNode license = pkg.path("license");
        if (!license.isArray() || license.isEmpty()) {
            return null;
        }
        List<String> licenses = new ArrayList<>();
        for (JsonNode entry : license) {
            String value = entry.asText(null);
            if (value != null && !value.isBlank()) {
                licenses.add(value.strip());
            }
        }
        return licenses.isEmpty() ? null : licenses;
    }

    /** Strips the optional leading "v" from a composer version (composer normalizes v1.2.3 → 1.2.3). */
    private static String normalizeComposerVersion(String version) {
        if (version != null && version.length() > 1
                && version.charAt(0) == 'v' && Character.isDigit(version.charAt(1))) {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Parses {@code conan.lock} (JSON) — Conan 2.x: flat {@code requires} /
     * {@code build_requires} (tagged scope=dev) ref arrays; Conan 1.x: {@code graph_lock.nodes[*].ref}.
     * Refs look like {@code name/version[@user/channel][#revision][%timestamp]}; only the
     * {@code name} and {@code version} parts are kept (OSV ecosystem "ConanCenter" uses plain names).
     */
    private List<ScanPayload.ComponentPayload> parseConanLock(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("conan.lock").toFile());
            return parseConanLockJson(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Conan] Failed to parse conan.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private List<ScanPayload.ComponentPayload> parseConanLockJson(JsonNode root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (root.has("graph_lock")) {
            root.path("graph_lock").path("nodes").properties().forEach(e ->
                    addConanRef(comps, seen, e.getValue().path("ref").asText(null), null));
        } else {
            addConanRefs(comps, seen, root.path("requires"), null);
            addConanRefs(comps, seen, root.path("build_requires"), "dev");
        }
        log.info("[DependencyParser][Conan] Parsed {} components from conan.lock in '{}'", comps.size(), repoName);
        return comps;
    }

    private void addConanRefs(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                              JsonNode refs, String scope) {
        if (refs == null || !refs.isArray()) {
            return;
        }
        for (JsonNode ref : refs) {
            addConanRef(comps, seen, ref.asText(null), scope);
        }
    }

    private void addConanRef(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                             String ref, String scope) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        String r = ref.trim();
        int hash = r.indexOf('#');
        if (hash >= 0) {
            r = r.substring(0, hash); // strip #revision%timestamp
        }
        int at = r.indexOf('@');
        if (at >= 0) {
            r = r.substring(0, at); // strip @user/channel
        }
        int slash = r.indexOf('/');
        if (slash <= 0 || slash == r.length() - 1) {
            return;
        }
        String name    = r.substring(0, slash).trim();
        String version = r.substring(slash + 1).trim();
        if (name.isBlank() || version.isBlank()) {
            return;
        }
        if (seen.add(name + ":" + version)) {
            comps.add(buildComponent(name, version, "CONAN").withScope(scope));
        }
    }

    /**
     * Parses an uploaded raw lock file (composer.lock / conan.lock), detected by content shape.
     * Used by the SBOM-upload import path. Returns {@code null} when the content is not a
     * recognized lock file so the caller can fall back to CycloneDX SBOM parsing.
     */
    public List<ScanPayload.ComponentPayload> parseUploadedLockFile(byte[] content, String label) {
        try {
            String head = new String(content, 0, Math.min(content.length, 200), StandardCharsets.UTF_8)
                    .stripLeading();
            if (!head.startsWith("{")) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(content);
            if (isComposerLockJson(root)) {
                return parseComposerLockJson(root, label);
            }
            if (isConanLockJson(root)) {
                return parseConanLockJson(root, label);
            }
        } catch (Exception e) {
            log.debug("[DependencyParser] Uploaded content is not a supported lock file: {}", e.getMessage());
        }
        return null;
    }

    /** composer.lock shape: "packages" array plus a composer-only marker key. */
    private static boolean isComposerLockJson(JsonNode root) {
        return root.path("packages").isArray()
                && (root.has("content-hash") || root.has("_readme") || root.has("packages-dev"));
    }

    /** conan.lock shape: Conan 1.x "graph_lock", or Conan 2.x flat "requires" array + "version". */
    private static boolean isConanLockJson(JsonNode root) {
        return root.has("graph_lock")
                || (root.path("requires").isArray() && root.has("version"));
    }

    // ── C/C++ manifest parsers ─────────────────────────────────────────────

    /**
     * Parses {@code vcpkg.json} manifest dependencies. Dependency entries may be plain names,
     * objects with a {@code name} and optional {@code version>=} constraint, or feature objects.
     * Only the declared name and best-effort version are emitted (vcpkg.json is not a lock file).
     */
    private List<ScanPayload.ComponentPayload> parseVcpkgJson(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("vcpkg.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            JsonNode deps = root.path("dependencies");
            if (deps.isArray()) {
                for (JsonNode dep : deps) {
                    String name = null;
                    String version = null;
                    if (dep.isTextual()) {
                        name = dep.asText(null);
                    } else if (dep.isObject()) {
                        name = dep.path("name").asText(null);
                        if (dep.has("version>=")) {
                            version = dep.path("version>=").asText(null);
                        } else if (dep.has("version")) {
                            version = dep.path("version").asText(null);
                        } else if (dep.has("baseline")) {
                            version = dep.path("baseline").asText(null);
                        }
                    }
                    if (name == null || name.isBlank()) continue;
                    name = name.trim();
                    version = (version != null && !version.isBlank()) ? version.trim() : null;
                    if (seen.add(name + ":" + (version != null ? version : ""))) {
                        comps.add(buildComponent(name, version, "VCPKG"));
                    }
                }
            }
            log.info("[DependencyParser][vcpkg] Parsed {} components from vcpkg.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][vcpkg] Failed to parse vcpkg.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /**
     * Parses {@code vcpkg-configuration.json}. The file mainly describes registries, but it can
     * contain named registry packages with versions. Best-effort parse: any object with both
     * {@code name} and a version-ish field is emitted.
     */
    private List<ScanPayload.ComponentPayload> parseVcpkgConfigurationJson(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("vcpkg-configuration.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            extractVcpkgRegistryPackages(root, seen, comps);
            log.info("[DependencyParser][vcpkg] Parsed {} components from vcpkg-configuration.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][vcpkg] Failed to parse vcpkg-configuration.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private void extractVcpkgRegistryPackages(JsonNode node, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        if (node == null || node.isMissingNode()) return;
        if (node.isArray()) {
            for (JsonNode item : node) {
                extractVcpkgRegistryPackage(item, seen, comps);
                extractVcpkgRegistryPackages(item, seen, comps);
            }
        } else if (node.isObject()) {
            extractVcpkgRegistryPackage(node, seen, comps);
            node.fields().forEachRemaining(e -> extractVcpkgRegistryPackages(e.getValue(), seen, comps));
        }
    }

    private void extractVcpkgRegistryPackage(JsonNode obj, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        if (!obj.isObject()) return;
        String name = obj.path("name").asText(null);
        if (name == null || name.isBlank()) return;
        String version = null;
        for (String key : new String[]{"version", "baseline", "version>="}) {
            if (obj.has(key)) {
                version = obj.path(key).asText(null);
                if (version != null && !version.isBlank()) break;
            }
        }
        String v = (version != null && !version.isBlank()) ? version.trim() : null;
        if (seen.add(name + ":" + (v != null ? v : ""))) {
            comps.add(buildComponent(name, v, "VCPKG"));
        }
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
        Path cmake = dir.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(cmake)) return List.of();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            String content = Files.readString(cmake, StandardCharsets.UTF_8);
            // Normalize line continuations and remove CMake comments
            content = content.replaceAll("\\\\\\r?\\n", " ");
            content = content.replaceAll("#[^\\n]*", " ");
            parseCMakeCommand(content, "FetchContent_Declare", seen, comps);
            parseCMakeCommand(content, "ExternalProject_Add", seen, comps);
            log.info("[DependencyParser][CMake] Parsed {} components from CMakeLists.txt in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][CMake] Failed to parse CMakeLists.txt for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private void parseCMakeCommand(String content, String command, Set<String> seen,
                                   List<ScanPayload.ComponentPayload> comps) {
        // Match command(ident ... ) allowing nested parentheses one level deep.
        Pattern cmdP = Pattern.compile(
                "\\b" + Pattern.quote(command) + "\\s*\\(\\s*([A-Za-z0-9_\\-]+)\\s+((?:[^()]|\\([^()]*\\))*)\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = cmdP.matcher(content);
        while (m.find()) {
            String ident = m.group(1);
            String body = m.group(2);
            String gitRepo = extractCMakeQuotedArg(body, "GIT_REPOSITORY");
            String gitTag = extractCMakeQuotedArg(body, "GIT_TAG");
            String url = extractCMakeQuotedArg(body, "URL");
            String name = cmakeBasenameFromUrl(gitRepo != null ? gitRepo : url);
            if (name == null) name = ident;
            String version = null;
            if (gitTag != null && !gitTag.isBlank()) {
                version = normalizeGitTag(gitTag);
            } else if (url != null && !url.isBlank()) {
                version = versionFromArchiveUrl(url);
            }
            if (version == null || version.isBlank()) continue;
            if (seen.add(name + ":" + version)) {
                comps.add(buildComponent(name, version, "VENDORED"));
            }
        }
    }

    /**
     * ROADMAP A3: OS-package inventory from a Dockerfile's base image + explicitly
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
        Pattern p = Pattern.compile(
                "\\b" + Pattern.quote(key) + "\\s+\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private String cmakeBasenameFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.replaceAll("\\.git$", "");
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) return trimmed;
        String base = trimmed.substring(slash + 1);
        return base.isBlank() ? null : base;
    }

    private String normalizeGitTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.startsWith("v") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
            return t.substring(1);
        }
        return t;
    }

    private String versionFromArchiveUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String base = cmakeBasenameFromUrl(url);
        if (base == null) return null;
        // Strip common archive suffixes and look for a version tail: name-1.2.3.tar.gz
        String withoutExt = base.replaceAll("\\.(tar\\.gz|tar\\.bz2|tar\\.xz|zip|tgz|tbz2|txz)$", "");
        int dash = withoutExt.lastIndexOf('-');
        if (dash > 0 && dash < withoutExt.length() - 1) {
            String candidate = withoutExt.substring(dash + 1);
            if (candidate.matches("[0-9].*")) return candidate;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }

    /** Normalizes a Maven {@code <scope>} to the noise-cut tag; compile/runtime → null (production). */
    private static String normalizeMavenScope(String scope) {
        if (scope == null || scope.isBlank()) return null;
        String s = scope.trim().toLowerCase();
        return switch (s) {
            case "compile", "runtime", "import" -> null;
            default -> s; // test, provided, system
        };
    }

    /** Builds a scan payload from parse results (version label supplied by caller). */
    public ScanPayload buildScanPayload(ParseResult deps, String version) {
        return ScanPayload.create(version, deps.components());
    }
}
