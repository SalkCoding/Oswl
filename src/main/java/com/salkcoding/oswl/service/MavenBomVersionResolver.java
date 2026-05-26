package com.salkcoding.oswl.service;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Resolves Maven {@code groupId:artifactId} versions from BOM / parent POMs when build
 * manifests omit explicit versions (Spring Boot, {@code io.spring.dependency-management}, etc.).
 */
@Slf4j
@Service
public class MavenBomVersionResolver {

    private static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2";
    private static final int MAX_BOM_DEPTH = 10;

    private static final Pattern SPRING_BOOT_PLUGIN = Pattern.compile(
            "org\\.springframework\\.boot[\"']?\\)?\\s+version\\s+['\"]([^'\"]+)['\"]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MAVEN_BOM_IMPORT = Pattern.compile(
            "mavenBom\\s*[\"']([\\w.\\-]+:[\\w.\\-]+):([\\w.+\\-]+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GRADLE_DEP = Pattern.compile(
            "(?:implementation|api|runtimeOnly|compileOnly|annotationProcessor|" +
            "testImplementation|testRuntimeOnly|testCompileOnly|" +
            "kapt|ksp|developmentOnly|" +
            "(?:androidTest|debug|release)Implementation|" +
            "[A-Za-z][A-Za-z0-9_]*Implementation)" +
            "\\s*\\(?[\"']([\\w.\\-]+:[\\w.\\-]+)(?::([\\w.+\\-]+))?[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROP_REF = Pattern.compile("\\$\\{([^}]+)}");

    private final RestClient restClient;
    private final PomFetcher pomFetcher;

    public MavenBomVersionResolver() {
        this.restClient = RestClient.builder().build();
        this.pomFetcher = this::fetchPomFromMavenCentral;
    }

    /** Visible for unit tests — inject a custom POM loader to avoid network I/O. */
    MavenBomVersionResolver(PomFetcher pomFetcher) {
        this.restClient = RestClient.builder().build();
        this.pomFetcher = pomFetcher;
    }

    /**
     * Builds a {@code groupId:artifactId → version} index for a cloned project directory
     * by reading Gradle/Maven manifests and fetching BOM POMs from Maven Central.
     */
    public Map<String, String> buildVersionIndex(Path projectDir) {
        Map<String, String> index = new LinkedHashMap<>();
        try {
            mergeVersionCatalogs(projectDir, index);
            mergeGradleProperties(projectDir, index);
            mergeGradleBomHints(projectDir, index);
            mergeMavenPomHints(projectDir, index);
        } catch (Exception e) {
            log.warn("[BOM] Failed to build version index under {}: {}", projectDir, e.getMessage());
        }
        log.info("[BOM] Version index size={} for {}", index.size(), projectDir.getFileName());
        return index;
    }

    /**
     * Fills missing versions on scan components using {@link #buildVersionIndex(Path)}.
     */
    public List<ScanPayload.ComponentPayload> enrichComponentVersions(
            Path projectDir, List<ScanPayload.ComponentPayload> components) {
        if (components == null || components.isEmpty()) {
            return components == null ? List.of() : components;
        }
        Map<String, String> index = buildVersionIndex(projectDir);
        if (index.isEmpty()) {
            return components;
        }
        List<ScanPayload.ComponentPayload> result = new ArrayList<>(components.size());
        int resolved = 0;
        for (ScanPayload.ComponentPayload c : components) {
            String version = resolveVersion(c.getVersion(), c.getName(), index);
            boolean wasMissing = c.getVersion() == null || c.getVersion().isBlank()
                    || isUnresolvedPlaceholder(c.getVersion());
            if (wasMissing && version != null && !version.isBlank()) {
                resolved++;
            }
            List<List<ScanPayload.DependencyNodeRef>> paths = enrichDependencyPaths(
                    c.getDependencyPaths(), index);
            result.add(ScanPayload.ComponentPayload.create(
                    c.getName(), version, c.getEcosystem(), c.getDependencyInfo(), paths));
        }
        if (resolved > 0) {
            log.info("[BOM] Resolved {} component version(s) in {}", resolved, projectDir.getFileName());
        }
        return result;
    }

    public String resolveVersion(String declaredVersion, String groupArtifact, Map<String, String> index) {
        if (declaredVersion != null && !declaredVersion.isBlank() && !isUnresolvedPlaceholder(declaredVersion)) {
            return declaredVersion;
        }
        int colon = groupArtifact.indexOf(':');
        if (colon <= 0) {
            return declaredVersion;
        }
        return lookup(index, groupArtifact.substring(0, colon), groupArtifact.substring(colon + 1));
    }

    // ── Index construction ───────────────────────────────────────────────────

    private void mergeVersionCatalogs(Path projectDir, Map<String, String> index) {
        List<Path> catalogs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir, 4)) {
            walk.filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".versions.toml"))
                    .forEach(catalogs::add);
        } catch (Exception e) {
            log.debug("[BOM] Version catalog walk failed: {}", e.getMessage());
            return;
        }
        for (Path catalog : catalogs) {
            try {
                List<String> lines = Files.readAllLines(catalog, StandardCharsets.UTF_8);
                Map<String, String> versions = new LinkedHashMap<>();
                boolean inVersions = false, inLibraries = false;
                for (String rawLine : lines) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.equals("[versions]")) {
                        inVersions = true;
                        inLibraries = false;
                        continue;
                    }
                    if (line.equals("[libraries]")) {
                        inLibraries = true;
                        inVersions = false;
                        continue;
                    }
                    if (line.startsWith("[")) {
                        inVersions = false;
                        inLibraries = false;
                        continue;
                    }
                    if (inVersions) {
                        Matcher vm = Pattern.compile("^([\\w.\\-]+)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(line);
                        if (vm.find()) {
                            versions.put(vm.group(1), vm.group(2));
                        }
                    } else if (inLibraries) {
                        Matcher shortM = Pattern.compile(
                                "^[\\w.\\-]+\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+):([\\w.+\\-]+)[\"']").matcher(line);
                        if (shortM.find()) {
                            put(index, shortM.group(1), shortM.group(2));
                            continue;
                        }
                        Matcher tableM = Pattern.compile("^[\\w.\\-]+\\s*=\\s*\\{(.+)\\}").matcher(line);
                        if (tableM.find()) {
                            String body = tableM.group(1);
                            Matcher modM = Pattern.compile("module\\s*=\\s*[\"']([\\w.\\-]+:[\\w.\\-]+)[\"']").matcher(body);
                            if (modM.find()) {
                                String module = modM.group(1);
                                String version = null;
                                Matcher vRefM = Pattern.compile("version\\.ref\\s*=\\s*[\"']([\\w.\\-]+)[\"']").matcher(body);
                                Matcher vM = Pattern.compile("(?<![.\\w])version\\s*=\\s*[\"']([\\w.+\\-]+)[\"']").matcher(body);
                                if (vRefM.find()) {
                                    version = versions.get(vRefM.group(1));
                                } else if (vM.find()) {
                                    version = vM.group(1);
                                }
                                if (version != null && !version.isBlank()) {
                                    int c = module.indexOf(':');
                                    if (c > 0) {
                                        put(index, module.substring(0, c), module.substring(c + 1), version);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[BOM] Failed to parse catalog {}: {}", catalog, e.getMessage());
            }
        }
    }

    private void mergeGradleProperties(Path projectDir, Map<String, String> index) {
        Path propsFile = projectDir.resolve("gradle.properties");
        if (!Files.isRegularFile(propsFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(propsFile, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int eq = line.indexOf('=');
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (val.startsWith("\"") && val.endsWith("\"")) {
                    val = val.substring(1, val.length() - 1);
                }
                // Not a direct ga mapping — stored for ${} resolution only via props map if needed later
                if (key.endsWith(".version") && val.matches("[\\w.+\\-]+")) {
                    log.debug("[BOM] gradle.properties {}={}", key, val);
                }
            }
        } catch (Exception e) {
            log.debug("[BOM] gradle.properties read failed: {}", e.getMessage());
        }
    }

    private void mergeGradleBomHints(Path projectDir, Map<String, String> index) {
        List<Path> gradleFiles = findGradleBuildFiles(projectDir);
        Set<String> bomCoords = new LinkedHashSet<>();
        for (Path file : gradleFiles) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Matcher sb = SPRING_BOOT_PLUGIN.matcher(content);
                if (sb.find()) {
                    String ver = sb.group(1).trim();
                    bomCoords.add("org.springframework.boot:spring-boot-dependencies:" + ver);
                    log.debug("[BOM] Spring Boot plugin {} in {}", ver, file.getFileName());
                }
                Matcher bom = MAVEN_BOM_IMPORT.matcher(content);
                while (bom.find()) {
                    bomCoords.add(bom.group(1) + ":" + bom.group(2));
                }
            } catch (Exception e) {
                log.debug("[BOM] Failed to read {}: {}", file, e.getMessage());
            }
        }
        for (String coord : bomCoords) {
            String[] parts = coord.split(":");
            if (parts.length == 3) {
                mergeManagedVersionsFromPom(parts[0], parts[1], parts[2], index, new HashSet<>(), 0);
            }
        }
    }

    private void mergeMavenPomHints(Path projectDir, Map<String, String> index) {
        List<Path> poms = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectDir, 5)) {
            walk.filter(p -> !Files.isDirectory(p) && "pom.xml".equals(p.getFileName().toString()))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .forEach(poms::add);
        } catch (Exception e) {
            return;
        }
        for (Path pom : poms) {
            try {
                Document doc = parseXml(Files.readAllBytes(pom));
                Element project = doc.getDocumentElement();
                Element parent = directChild(project, "parent");
                if (parent != null) {
                    String g = text(directChild(parent, "groupId"));
                    String a = text(directChild(parent, "artifactId"));
                    String v = text(directChild(parent, "version"));
                    if (g != null && a != null && v != null) {
                        mergeManagedVersionsFromPom(g, a, v, index, new HashSet<>(), 0);
                    }
                }
                Map<String, String> props = extractProperties(project);
                Element dmRoot = directChild(project, "dependencyManagement");
                if (dmRoot != null) {
                    mergeDependencyManagement(directChild(dmRoot, "dependencies"), props, index, new HashSet<>(), 0);
                }
            } catch (Exception e) {
                log.debug("[BOM] pom.xml {} skipped: {}", pom, e.getMessage());
            }
        }
    }

    private void mergeManagedVersionsFromPom(
            String groupId, String artifactId, String version,
            Map<String, String> index, Set<String> visited, int depth) {
        if (depth > MAX_BOM_DEPTH) {
            return;
        }
        String visitKey = groupId + ":" + artifactId + ":" + version;
        if (!visited.add(visitKey)) {
            return;
        }
        Optional<byte[]> pomBytes = pomFetcher.fetch(groupId, artifactId, version);
        if (pomBytes.isEmpty()) {
            log.debug("[BOM] POM not found {}:{}:{}", groupId, artifactId, version);
            return;
        }
        try {
            Document doc = parseXml(pomBytes.get());
            Element project = doc.getDocumentElement();
            Map<String, String> props = extractProperties(project);

            Element parent = directChild(project, "parent");
            if (parent != null) {
                String pg = resolveProp(text(directChild(parent, "groupId")), props);
                String pa = resolveProp(text(directChild(parent, "artifactId")), props);
                String pv = resolveProp(text(directChild(parent, "version")), props);
                if (pg != null && pa != null && pv != null) {
                    mergeManagedVersionsFromPom(pg, pa, pv, index, visited, depth + 1);
                }
            }

            Element dmRoot = directChild(project, "dependencyManagement");
            if (dmRoot != null) {
                mergeDependencyManagement(directChild(dmRoot, "dependencies"), props, index, visited, depth + 1);
            }
        } catch (Exception e) {
            log.debug("[BOM] Failed to parse POM {}:{}:{} — {}", groupId, artifactId, version, e.getMessage());
        }
    }

    private void mergeDependencyManagement(
            Element depsRoot, Map<String, String> props,
            Map<String, String> index, Set<String> visited, int depth) {
        if (depsRoot == null || depth > MAX_BOM_DEPTH) {
            return;
        }
        NodeList children = depsRoot.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element dep) || !"dependency".equals(dep.getTagName())) {
                continue;
            }
            String g = resolveProp(text(directChild(dep, "groupId")), props);
            String a = resolveProp(text(directChild(dep, "artifactId")), props);
            String v = resolveProp(text(directChild(dep, "version")), props);
            String type = resolveProp(text(directChild(dep, "type")), props);
            String scope = resolveProp(text(directChild(dep, "scope")), props);
            if (g == null || a == null || v == null) {
                continue;
            }
            if ("pom".equalsIgnoreCase(type) && "import".equalsIgnoreCase(scope)) {
                mergeManagedVersionsFromPom(g, a, v, index, visited, depth);
            } else if (!"pom".equalsIgnoreCase(type) || type == null) {
                put(index, g, a, v);
            }
        }
    }

    // ── POM / XML helpers ────────────────────────────────────────────────────

    private Optional<byte[]> fetchPomFromMavenCentral(String groupId, String artifactId, String version) {
        String path = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
                + artifactId + "-" + version + ".pom";
        String url = MAVEN_CENTRAL + "/" + path;
        try {
            byte[] body = restClient.get().uri(url).retrieve().body(byte[].class);
            return body != null && body.length > 0 ? Optional.of(body) : Optional.empty();
        } catch (RestClientException e) {
            log.debug("[BOM] HTTP fetch failed {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        dbf.setNamespaceAware(false);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private Map<String, String> extractProperties(Element project) {
        Map<String, String> props = new HashMap<>();
        Element properties = directChild(project, "properties");
        if (properties == null) {
            return props;
        }
        NodeList children = properties.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element pe) {
                props.put(pe.getTagName(), pe.getTextContent().trim());
            }
        }
        return props;
    }

    private String resolveProp(String raw, Map<String, String> props) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        Matcher m = PROP_REF.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String val = props.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private Element directChild(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element e && tag.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private String text(Element el) {
        return el != null ? el.getTextContent().trim() : null;
    }

    // ── Component enrichment ─────────────────────────────────────────────────

    private List<List<ScanPayload.DependencyNodeRef>> enrichDependencyPaths(
            List<List<ScanPayload.DependencyNodeRef>> paths, Map<String, String> index) {
        if (paths == null || paths.isEmpty()) {
            return paths;
        }
        List<List<ScanPayload.DependencyNodeRef>> out = new ArrayList<>(paths.size());
        for (List<ScanPayload.DependencyNodeRef> path : paths) {
            if (path == null) {
                out.add(null);
                continue;
            }
            List<ScanPayload.DependencyNodeRef> newPath = new ArrayList<>(path.size());
            for (ScanPayload.DependencyNodeRef node : path) {
                String ver = resolveVersion(node.getVersion(), node.getName(), index);
                newPath.add(ScanPayload.DependencyNodeRef.create(node.getName(), ver));
            }
            out.add(newPath);
        }
        return out;
    }

    private static boolean isUnresolvedPlaceholder(String version) {
        return version.contains("${") || "unspecified".equalsIgnoreCase(version)
                || "unknown".equalsIgnoreCase(version) || "unresolved".equalsIgnoreCase(version);
    }

    private static String lookup(Map<String, String> index, String groupId, String artifactId) {
        String v = index.get(groupId + ":" + artifactId);
        if (v != null) {
            return v;
        }
        // Spring Boot starters sometimes omit classifier; BOM lists base artifact only
        return null;
    }

    private static void put(Map<String, String> index, String groupArtifact, String version) {
        int c = groupArtifact.indexOf(':');
        if (c > 0) {
            put(index, groupArtifact.substring(0, c), groupArtifact.substring(c + 1), version);
        }
    }

    private static void put(Map<String, String> index, String groupId, String artifactId, String version) {
        if (groupId == null || artifactId == null || version == null || version.isBlank()) {
            return;
        }
        index.putIfAbsent(groupId + ":" + artifactId, version);
    }

    private static List<Path> findGradleBuildFiles(Path projectDir) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(projectDir)) {
            stream.filter(p -> {
                if (Files.isDirectory(p)) {
                    return false;
                }
                String name = p.getFileName().toString();
                if (!name.equals("build.gradle") && !name.equals("build.gradle.kts")) {
                    return false;
                }
                String rel = projectDir.relativize(p).toString().replace('\\', '/');
                return !rel.contains("/build/") && !rel.contains("/.gradle/");
            }).forEach(files::add);
        } catch (Exception e) {
            log.debug("[BOM] Gradle file walk failed: {}", e.getMessage());
        }
        return files;
    }

    /**
     * Parses declared Gradle dependencies from build files and applies the BOM index.
     * Used by CLI-equivalent static collection when {@code gradlew} is unavailable.
     */
    public List<ScanPayload.ComponentPayload> parseGradleDeclaredWithBom(Path projectDir) {
        Map<String, String> index = buildVersionIndex(projectDir);
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path buildFile : findGradleBuildFiles(projectDir)) {
            try {
                String content = Files.readString(buildFile, StandardCharsets.UTF_8);
                Matcher m = GRADLE_DEP.matcher(content);
                while (m.find()) {
                    String ga = m.group(1);
                    String declared = m.group(2);
                    String version = resolveVersion(declared, ga, index);
                    String key = ga + ":" + (version != null ? version : "");
                    if (seen.add(key)) {
                        comps.add(ScanPayload.ComponentPayload.create(
                                ga, version, "MAVEN", "Gradle (declared + BOM)", List.of()));
                    }
                }
            } catch (Exception e) {
                log.debug("[BOM] Static Gradle parse failed for {}: {}", buildFile, e.getMessage());
            }
        }
        return comps;
    }

    @FunctionalInterface
    interface PomFetcher {
        Optional<byte[]> fetch(String groupId, String artifactId, String version);
    }
}
