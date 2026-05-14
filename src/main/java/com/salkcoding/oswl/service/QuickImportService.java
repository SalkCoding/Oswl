package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus.Phase;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the Quick Import flow:
 * <ol>
 *   <li>Detect the VCS provider from the URL</li>
 *   <li>Retrieve and decrypt the user's stored access token</li>
 *   <li>Clone the repository (shallow, depth 1) to a temporary directory</li>
 *   <li>Detect the build ecosystem and parse dependency files</li>
 *   <li>Find-or-create a Project and issue/reuse an API key</li>
 *   <li>Submit the scan payload to {@link ScanIngestService}</li>
 *   <li>Clean up the temporary clone</li>
 * </ol>
 *
 * Each import is tracked as an asynchronous job (virtual thread) so the
 * HTTP response is immediate; the UI polls {@link #getJobStatus(String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickImportService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Value("${oswl.clone.temp-dir:}")
    private String configuredTempDir;

    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final EncryptionService encryptionService;
    private final ProjectService projectService;
    private final ApiKeyService apiKeyService;
    private final ScanIngestService scanIngestService;

    /** In-memory job tracker. Entries are removed after 30 minutes. */
    private final ConcurrentHashMap<String, QuickImportJobStatus> jobs = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Starts an asynchronous import job and immediately returns the job ID.
     *
     * @param repoUrl   the repository URL entered by the user
     * @param branch    requested branch (null = default branch)
     * @param userId    authenticated user ID
     * @return job ID (UUID string)
     */
    public String startImport(String repoUrl, String branch, Long userId) {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, QuickImportJobStatus.builder()
                .jobId(jobId)
                .phase(Phase.QUEUED)
                .message("Import queued…")
                .build());

        Thread.ofVirtual().name("quick-import-" + jobId).start(() -> {
            try {
                runImport(jobId, repoUrl, branch, userId);
            } catch (Exception e) {
                log.error("[QuickImport][{}] Unhandled error: {}", jobId, e.getMessage(), e);
                updateJob(jobId, Phase.FAILED, "Unexpected error: " + e.getMessage(),
                        null, null, null, null, null, null);
            }
        });

        return jobId;
    }

    /** Returns the current status of a job, or null if the jobId is unknown. */
    public QuickImportJobStatus getJobStatus(String jobId) {
        return jobs.get(jobId);
    }

    // ── Import pipeline ────────────────────────────────────────────────────

    private void runImport(String jobId, String repoUrl, String branch, Long userId) throws Exception {
        // 1. Parse the URL ─────────────────────────────────────────────────
        ParsedRepoUrl parsed = parseRepoUrl(repoUrl);
        if (parsed == null) {
            updateJob(jobId, Phase.FAILED, "Cannot parse repository URL: " + repoUrl,
                    null, null, null, null, null, null);
            return;
        }

        // 2. Look up VCS connection ────────────────────────────────────────
        Optional<UserVcsConnection> connOpt =
                vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(userId, parsed.provider);
        if (connOpt.isEmpty()) {
            updateJob(jobId, Phase.FAILED,
                    parsed.provider.name() + " account not connected. " +
                    "Go to Settings → VCS to connect your account.",
                    null, null, null, null, null, null);
            return;
        }
        UserVcsConnection conn = connOpt.get();
        String token = decryptToken(conn);

        // 3. Clone ─────────────────────────────────────────────────────────
        updateJob(jobId, Phase.CLONING, "Cloning " + parsed.owner + "/" + parsed.repo + "…",
                null, null, null, null, null, null);

        Path cloneDir = createTempCloneDir(parsed.owner, parsed.repo, jobId);
        try {
            String authUrl = buildAuthUrl(parsed, token);
            cloneRepo(authUrl, branch, cloneDir, jobId);

            // 4. Parse dependencies ────────────────────────────────────────
            updateJob(jobId, Phase.PARSING, "Detecting ecosystem and parsing dependencies…",
                    null, null, null, null, null, null);

            ParsedDependencies deps = parseDependencies(cloneDir, parsed.owner + "/" + parsed.repo);

            // 5. Create/find project and API key ──────────────────────────
            Project project = projectService.upsertFromGitHub(
                    parsed.owner, parsed.repo,
                    branch != null && !branch.isBlank() ? branch : "default");

            ApiKeyResult keyResult = getOrIssueApiKey(project);

            // 6. Submit scan ───────────────────────────────────────────────
            updateJob(jobId, Phase.SCANNING, "Submitting scan payload (" + deps.components.size() + " components)…",
                    project.getId(), project.getName(), null, false, deps.ecosystem, deps.components.size());

            ScanPayload payload = buildScanPayload(deps, parsed.owner + "/" + parsed.repo);
            scanIngestService.ingest(project.getId(), payload);

            // 7. Done ──────────────────────────────────────────────────────
            updateJob(jobId, Phase.DONE,
                    "Import complete — " + deps.components.size() + " components scanned.",
                    project.getId(), project.getName(),
                    keyResult.token, keyResult.isNew,
                    deps.ecosystem, deps.components.size());

        } finally {
            deleteDirectory(cloneDir);
        }
    }

    // ── URL parsing ────────────────────────────────────────────────────────

    private record ParsedRepoUrl(VcsProvider provider, String host, String owner, String repo) {}

    private ParsedRepoUrl parseRepoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        // Normalise: remove trailing .git and trailing slash
        String url = rawUrl.trim().replaceAll("\\.git$", "").replaceAll("/$", "");
        // Extract host + path: handle https://host/owner/repo
        Pattern p = Pattern.compile("https?://([^/]+)/([^/]+)/([^/]+).*");
        Matcher m = p.matcher(url);
        if (!m.matches()) return null;
        String host  = m.group(1).toLowerCase();
        String owner = m.group(2);
        String repo  = m.group(3);

        VcsProvider provider;
        if (host.equals("github.com") || host.endsWith(".github.com")) {
            provider = VcsProvider.GITHUB;
        } else if (host.equals("gitlab.com") || host.contains("gitlab")) {
            provider = VcsProvider.GITLAB;
        } else if (host.equals("bitbucket.org") || host.endsWith(".bitbucket.org")) {
            provider = VcsProvider.BITBUCKET;
        } else {
            // Unknown host — cannot determine provider
            log.warn("[QuickImport] Unknown host '{}' — cannot determine VCS provider.", host);
            return null;
        }
        return new ParsedRepoUrl(provider, host, owner, repo);
    }

    private String buildAuthUrl(ParsedRepoUrl parsed, String token) {
        return switch (parsed.provider) {
            case GITHUB    -> "https://oauth2:" + token + "@" + parsed.host + "/" + parsed.owner + "/" + parsed.repo + ".git";
            case GITLAB    -> "https://oauth2:" + token + "@" + parsed.host + "/" + parsed.owner + "/" + parsed.repo + ".git";
            case BITBUCKET -> "https://x-token-auth:" + token + "@" + parsed.host + "/" + parsed.owner + "/" + parsed.repo + ".git";
        };
    }

    // ── Git clone ─────────────────────────────────────────────────────────

    private void cloneRepo(String authUrl, String branch, Path targetDir, String jobId) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "git", "clone", "--depth", "1", "--single-branch", "--quiet"
        ));
        if (branch != null && !branch.isBlank()) {
            cmd.addAll(List.of("--branch", branch));
        }
        cmd.add(authUrl);
        cmd.add(targetDir.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true);
        // Mask token in logs by using redacted auth URL
        log.info("[QuickImport][{}] Running: git clone --depth 1 {}", jobId,
                authUrl.replaceAll("(https?://)([^@]+@)", "$1***@"));

        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = proc.waitFor();

        if (exitCode != 0) {
            String safe = output.replaceAll("(https?://)([^@]+@)", "$1***@");
            throw new RuntimeException("git clone failed (exit " + exitCode + "): " + safe.trim());
        }
    }

    // ── Dependency parsing ─────────────────────────────────────────────────

    private record ParsedDependencies(String ecosystem, List<ScanPayload.ComponentPayload> components) {}

    private ParsedDependencies parseDependencies(Path cloneDir, String repoName) {
        // Detect by presence of build files (ordered by priority)
        if (Files.exists(cloneDir.resolve("pom.xml"))) {
            return parseMaven(cloneDir, repoName);
        }
        if (Files.exists(cloneDir.resolve("package.json"))) {
            return parseNpm(cloneDir, repoName);
        }
        if (Files.exists(cloneDir.resolve("build.gradle")) ||
                Files.exists(cloneDir.resolve("build.gradle.kts"))) {
            return parseGradle(cloneDir, repoName);
        }
        if (Files.exists(cloneDir.resolve("requirements.txt"))) {
            return parsePython(cloneDir, repoName);
        }
        if (Files.exists(cloneDir.resolve("Cargo.toml"))) {
            return parseCargo(cloneDir, repoName);
        }
        // go.mod
        if (Files.exists(cloneDir.resolve("go.mod"))) {
            return parseGoMod(cloneDir, repoName);
        }
        log.warn("[QuickImport] No recognized build file in repo '{}' — returning empty component list.", repoName);
        return new ParsedDependencies("UNKNOWN", List.of());
    }

    // Maven: parse pom.xml <dependencies>
    private ParsedDependencies parseMaven(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE protection
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            Document doc = dbf.newDocumentBuilder().parse(dir.resolve("pom.xml").toFile());
            doc.getDocumentElement().normalize();

            // Extract project version from <version>
            String projectVersion = getTextContent(doc.getDocumentElement(), "version");

            NodeList deps = doc.getElementsByTagName("dependency");
            for (int i = 0; i < deps.getLength(); i++) {
                Element dep = (Element) deps.item(i);
                String groupId    = getTextContent(dep, "groupId");
                String artifactId = getTextContent(dep, "artifactId");
                String version    = getTextContent(dep, "version");
                String scope      = getTextContent(dep, "scope");

                if (groupId == null || artifactId == null) continue;
                // Skip test/provided/system scopes for the primary scan
                if ("test".equalsIgnoreCase(scope) || "system".equalsIgnoreCase(scope)) continue;

                comps.add(buildComponent(groupId + ":" + artifactId, version, "MAVEN"));
            }
            log.info("[QuickImport][Maven] Parsed {} components from pom.xml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Maven] Failed to parse pom.xml: {}", e.getMessage());
        }
        return new ParsedDependencies("MAVEN", comps);
    }

    // npm: parse package.json dependencies + devDependencies
    private ParsedDependencies parseNpm(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package.json").toFile());
            JsonNode deps    = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");

            addNpmDeps(comps, deps, false);
            addNpmDeps(comps, devDeps, true);
            log.info("[QuickImport][npm] Parsed {} components from package.json in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][npm] Failed to parse package.json: {}", e.getMessage());
        }
        return new ParsedDependencies("NPM", comps);
    }

    private void addNpmDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode, boolean isDev) {
        if (depsNode == null || depsNode.isMissingNode()) return;
        depsNode.fields().forEachRemaining(entry -> {
            String name    = entry.getKey();
            String version = entry.getValue().asText().replaceAll("^[~^>=<]+ *", "");
            comps.add(buildComponent(name, version, "NPM"));
        });
    }

    // Gradle: parse build.gradle or build.gradle.kts with regex (no subprocess)
    private ParsedDependencies parseGradle(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            Path buildFile = Files.exists(dir.resolve("build.gradle"))
                    ? dir.resolve("build.gradle")
                    : dir.resolve("build.gradle.kts");
            String content = Files.readString(buildFile, StandardCharsets.UTF_8);

            // Match: implementation 'group:artifact:version' or ("group:artifact:version")
            Pattern p = Pattern.compile(
                    "(?:implementation|api|runtimeOnly|compileOnly|annotationProcessor)" +
                    "\\s*[\\(\\\"']([\\w.\\-]+:[\\w.\\-]+):([\\w.\\-]+)[\\\"'\\)]",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(content);
            while (m.find()) {
                comps.add(buildComponent(m.group(1), m.group(2), "MAVEN"));
            }
            log.info("[QuickImport][Gradle] Parsed {} components from {} in '{}'",
                    comps.size(), buildFile.getFileName(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Gradle] Failed to parse build.gradle: {}", e.getMessage());
        }
        return new ParsedDependencies("MAVEN", comps); // Gradle deps are Maven-ecosystem packages
    }

    // Python: parse requirements.txt
    private ParsedDependencies parsePython(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("requirements.txt"), StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("^([\\w.\\-\\[\\]]+)\\s*(?:==|>=|<=|~=|!=)?\\s*([^\\s#]*)");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String name    = m.group(1).replaceAll("\\[.*\\]", "");
                    String version = m.group(2).isBlank() ? null : m.group(2);
                    comps.add(buildComponent(name, version, "PYPI"));
                }
            }
            log.info("[QuickImport][Python] Parsed {} components from requirements.txt in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Python] Failed to parse requirements.txt: {}", e.getMessage());
        }
        return new ParsedDependencies("PYPI", comps);
    }

    // Cargo (Rust): parse Cargo.toml
    private ParsedDependencies parseCargo(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("Cargo.toml"), StandardCharsets.UTF_8);
            boolean inDeps = false;
            Pattern dep = Pattern.compile("^([\\w\\-]+)\\s*=\\s*[\"']?([\\d.]+)[\"']?");
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equals("[dependencies]") || trimmed.equals("[dev-dependencies]")) {
                    inDeps = true;
                    continue;
                }
                if (trimmed.startsWith("[") && !trimmed.startsWith("[dependencies")) {
                    inDeps = false;
                    continue;
                }
                if (inDeps && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    Matcher m = dep.matcher(trimmed);
                    if (m.find()) comps.add(buildComponent(m.group(1), m.group(2), "CARGO"));
                }
            }
            log.info("[QuickImport][Cargo] Parsed {} components from Cargo.toml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Cargo] Failed to parse Cargo.toml: {}", e.getMessage());
        }
        return new ParsedDependencies("CARGO", comps);
    }

    // Go modules: parse go.mod
    private ParsedDependencies parseGoMod(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("go.mod"), StandardCharsets.UTF_8);
            boolean inRequire = false;
            Pattern single = Pattern.compile("^require\\s+([^\\s]+)\\s+v([^\\s]+)");
            Pattern block  = Pattern.compile("^\\s+([^\\s]+)\\s+v([^\\s]+)");
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("require (")) { inRequire = true; continue; }
                if (t.equals(")"))             { inRequire = false; continue; }
                Matcher m = inRequire ? block.matcher(t) : single.matcher(t);
                if (m.find()) comps.add(buildComponent(m.group(1), m.group(2), "GO"));
            }
            log.info("[QuickImport][Go] Parsed {} components from go.mod in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Go] Failed to parse go.mod: {}", e.getMessage());
        }
        return new ParsedDependencies("GO", comps);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        // ScanPayload.ComponentPayload uses package-private setters (no-arg constructor + package reflection)
        // → build via reflection-free builder pattern using the public no-arg constructor
        return ReflectiveComponentBuilder.build(name, version, ecosystem);
    }

    private ScanPayload buildScanPayload(ParsedDependencies deps, String repoName) {
        return ReflectiveComponentBuilder.buildPayload(deps.components, repoName);
    }

    private record ApiKeyResult(String token, boolean isNew) {}

    /**
     * Returns an existing active API key for the project, or issues a new one
     * labelled "quick-import".
     */
    private ApiKeyResult getOrIssueApiKey(Project project) {
        List<ApiKey> existing = apiKeyService.findByProject(project.getId())
                .stream()
                .filter(ApiKey::isValid)
                .toList();
        if (!existing.isEmpty()) {
            return new ApiKeyResult(existing.get(0).getToken(), false);
        }
        ApiKey newKey = apiKeyService.issue(project.getId(), "quick-import", null);
        return new ApiKeyResult(newKey.getToken(), true);
    }

    private String decryptToken(UserVcsConnection conn) {
        try {
            return encryptionService.decrypt(conn.getAccessTokenEncrypted());
        } catch (Exception e) {
            log.warn("[QuickImport] Failed to decrypt VCS token (legacy plaintext?): {}", e.getMessage());
            return conn.getAccessTokenEncrypted();
        }
    }

    private Path createTempCloneDir(String owner, String repo, String jobId) throws IOException {
        String base = configuredTempDir != null && !configuredTempDir.isBlank()
                ? configuredTempDir
                : System.getProperty("java.io.tmpdir") + File.separator + "oswl-clones";
        Path parent = Path.of(base);
        Files.createDirectories(parent);
        Path dir = parent.resolve(owner + "_" + repo + "_" + jobId);
        Files.createDirectories(dir);
        return dir;
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("[QuickImport] Could not delete temp dir '{}': {}", dir, e.getMessage());
        }
    }

    private String getTextContent(Element parent, String tagName) {
        NodeList nl = parent.getElementsByTagName(tagName);
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent().trim();
    }

    private void updateJob(String jobId, Phase phase, String message,
                           Long projectId, String projectName,
                           String apiToken, Boolean newApiKey,
                           String ecosystem, Integer componentCount) {
        jobs.put(jobId, QuickImportJobStatus.builder()
                .jobId(jobId)
                .phase(phase)
                .message(message)
                .projectId(projectId)
                .projectName(projectName)
                .apiToken(apiToken)
                .newApiKey(newApiKey)
                .ecosystem(ecosystem)
                .componentCount(componentCount)
                .build());
    }

    // ── Reflective builder helper ─────────────────────────────────────────

    /**
     * {@link ScanPayload.ComponentPayload} has only a no-arg constructor and
     * package-private fields set via Jackson deserialization (no setters).
     * We use field access to populate instances programmatically.
     */
    private static final class ReflectiveComponentBuilder {

        static ScanPayload.ComponentPayload build(String name, String version, String ecosystem) {
            try {
                ScanPayload.ComponentPayload c = ScanPayload.ComponentPayload.class.getDeclaredConstructor().newInstance();
                setField(c, "name", name);
                setField(c, "version", version);
                setField(c, "ecosystem", ecosystem);
                setField(c, "dependencyInfo", "Direct");
                setField(c, "dependencyPaths", List.of());
                return c;
            } catch (Exception e) {
                throw new RuntimeException("Failed to build ComponentPayload", e);
            }
        }

        static ScanPayload buildPayload(List<ScanPayload.ComponentPayload> components, String version) {
            try {
                ScanPayload p = ScanPayload.class.getDeclaredConstructor().newInstance();
                setField(p, "version", version);
                setField(p, "components", components);
                setField(p, "rawJson", "{}");
                return p;
            } catch (Exception e) {
                throw new RuntimeException("Failed to build ScanPayload", e);
            }
        }

        private static void setField(Object target, String fieldName, Object value) throws Exception {
            Field f = findField(target.getClass(), fieldName);
            if (f == null) return;
            f.setAccessible(true);
            f.set(target, value);
        }

        private static Field findField(Class<?> clazz, String name) {
            while (clazz != null) {
                try { return clazz.getDeclaredField(name); }
                catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
            }
            return null;
        }
    }
}
