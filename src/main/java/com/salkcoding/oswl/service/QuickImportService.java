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
import com.salkcoding.oswl.dto.QuickImportRepoDto;
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
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.scheduling.annotation.Scheduled;

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
    private static final String USER_AGENT = "OsWL-App/1.0";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${oswl.clone.temp-dir:}")
    private String configuredTempDir;

    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final EncryptionService encryptionService;
    private final ProjectService projectService;
    private final ApiKeyService apiKeyService;
    private final ScanIngestService scanIngestService;
    private final GitHubService gitHubService;

    /** In-memory job tracker. Entries are removed after 30 minutes by {@link #evictExpiredJobs()}. */
    private final ConcurrentHashMap<String, QuickImportJobStatus> jobs = new ConcurrentHashMap<>();
    /** Tracks job creation times for TTL-based eviction. */
    private final ConcurrentHashMap<String, Instant> jobCreatedAt = new ConcurrentHashMap<>();
    /** Prevents concurrent imports for the same user (at most one active job per user). */
    private final ConcurrentHashMap<Long, String> activeJobByUser = new ConcurrentHashMap<>();

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
        // Reject if the user already has a non-terminal job in flight
        String existingJobId = activeJobByUser.get(userId);
        if (existingJobId != null) {
            QuickImportJobStatus existing = jobs.get(existingJobId);
            if (existing != null && existing.getPhase() != Phase.DONE && existing.getPhase() != Phase.FAILED) {
                log.info("[QuickImport] User {} already has active job {}, returning it", userId, existingJobId);
                return existingJobId;
            }
            activeJobByUser.remove(userId);
        }

        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, QuickImportJobStatus.builder()
                .jobId(jobId)
                .phase(Phase.QUEUED)
                .message("Import queued…")
                .build());
        jobCreatedAt.put(jobId, Instant.now());
        activeJobByUser.put(userId, jobId);

        Thread.ofVirtual().name("quick-import-" + jobId).start(() -> {
            try {
                runImport(jobId, repoUrl, branch, userId);
            } catch (Exception e) {
                log.error("[QuickImport][{}] Unhandled error: {}", jobId, e.getMessage(), e);
                updateJob(jobId, Phase.FAILED, "Unexpected error: " + e.getMessage(),
                        null, null, null, null, null, null);
            } finally {
                activeJobByUser.remove(userId, jobId);
            }
        });

        return jobId;
    }

    /** Returns the current status of a job, or null if the jobId is unknown. */
    public QuickImportJobStatus getJobStatus(String jobId) {
        return jobs.get(jobId);
    }

    // ── Repo browser (for Quick Import list UI) ───────────────────────────

    /**
     * Returns all repositories accessible by the authenticated user for the given VCS provider.
     *
     * @param provider the VCS provider (GITHUB, GITLAB, BITBUCKET)
     * @param userId   authenticated user ID
     * @return list of repo DTOs, sorted by last updated, or empty list if no connection found
     */
    public List<QuickImportRepoDto> listRepos(VcsProvider provider, Long userId) {
        Optional<UserVcsConnection> connOpt = vcsConnectionRepository.findByUserIdAndActiveTrue(userId)
                .stream()
                .filter(c -> c.getProvider() == provider)
                .findFirst();

        if (connOpt.isEmpty()) return List.of();

        UserVcsConnection conn = connOpt.get();
        String token;
        try {
            token = encryptionService.decrypt(conn.getAccessTokenEncrypted());
        } catch (Exception e) {
            log.warn("[RepoBrowser] Failed to decrypt token for provider {}: {}", provider, e.getMessage());
            return List.of();
        }

        try {
            return switch (provider) {
                case GITHUB   -> listGitHubRepos(token);
                case GITLAB   -> listGitLabRepos(token, conn.getServerUrl());
                case BITBUCKET -> conn.getServerUrl() == null
                        ? listBitbucketCloudRepos(token, conn.getVcsUsername())
                        : listBitbucketServerRepos(token, conn.getServerUrl());
            };
        } catch (Exception e) {
            log.warn("[RepoBrowser] Failed to list repos for provider {}: {}", provider, e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private List<QuickImportRepoDto> listGitHubRepos(String token) {
        return gitHubService.listAllUserRepos(token).stream()
                .map(r -> new QuickImportRepoDto(
                        r.getName(),
                        r.getFullName(),
                        "https://github.com/" + r.getFullName(),
                        r.getDefaultBranch(),
                        r.isPrivate(),
                        r.getUpdatedAt()))
                .toList();
    }

    private List<QuickImportRepoDto> listGitLabRepos(String token, String serverUrl) throws Exception {
        String base = serverUrl != null ? serverUrl.replaceAll("/+$", "") : "https://gitlab.com";

        // membership=true works for classic PATs (user-level membership context).
        // Fine-grained project-scoped tokens lack that context — fall back to min_access_level.
        String url = base + "/api/v4/projects?membership=true&per_page=100&order_by=last_activity_at";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 403) {
            log.warn("[RepoBrowser] GitLab membership=true returned 403, retrying with min_access_level (fine-grained token)");
            return listGitLabReposByAccessLevel(token, base);
        }
        if (resp.statusCode() != 200) {
            log.warn("[RepoBrowser] GitLab returned HTTP {} \u2014 {}", resp.statusCode(), resp.body());
            throw new IOException("GitLab API returned HTTP " + resp.statusCode());
        }
        return parseGitLabProjects(resp.body());
    }

    private List<QuickImportRepoDto> listGitLabReposByAccessLevel(String token, String base) throws Exception {
        String url = base + "/api/v4/projects?min_access_level=10&per_page=100&order_by=last_activity_at";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("PRIVATE-TOKEN", token)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("[RepoBrowser] GitLab min_access_level returned HTTP {} \u2014 {}", resp.statusCode(), resp.body());
            throw new IOException("GitLab API returned HTTP " + resp.statusCode() +
                " \u2014 ensure the fine-grained token has \"Project: Read\" permission.");
        }
        return parseGitLabProjects(resp.body());
    }

    private List<QuickImportRepoDto> parseGitLabProjects(String body) throws Exception {
        JsonNode arr = OBJECT_MAPPER.readTree(body);
        if (!arr.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode r : arr) {
            result.add(new QuickImportRepoDto(
                    r.path("name").asText(),
                    r.path("path_with_namespace").asText(),
                    r.path("web_url").asText(),
                    r.path("default_branch").asText("main"),
                    r.path("visibility").asText("public").equals("private"),
                    r.path("last_activity_at").asText()));
        }
        return result;
    }

    private List<QuickImportRepoDto> listBitbucketCloudRepos(String tokenOrAppPassword, String username) throws Exception {
        String bearerHeader = "Bearer " + tokenOrAppPassword;
        // ATATT = Bitbucket HTTP Access Token (workspace / project / repo scoped) → must use Bearer.
        // ATBB  = Bitbucket App Password (user-level) → Basic auth with username:token.
        boolean isAtatt = tokenOrAppPassword != null && tokenOrAppPassword.startsWith("ATATT");
        boolean hasSlug = username != null && !username.isBlank() && !username.contains("@");

        // Case 1: workspace slug stored + ATATT token → Bearer auth against that workspace directly.
        if (hasSlug && isAtatt) {
            List<QuickImportRepoDto> repos = fetchBitbucketRepoPage(
                    "https://api.bitbucket.org/2.0/repositories/" + username + "?pagelen=100&sort=-updated_on",
                    bearerHeader);
            if (!repos.isEmpty()) return repos;
            // Fall through to generic discovery in case the slug is wrong.
        }

        // Case 2: App Password with username (Basic auth).
        if (hasSlug && !isAtatt) {
            String basicHeader = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + tokenOrAppPassword).getBytes(StandardCharsets.UTF_8));
            return fetchBitbucketRepoPage(
                    "https://api.bitbucket.org/2.0/repositories/" + username + "?pagelen=100&sort=-updated_on",
                    basicHeader);
        }

        // Case 3: No workspace slug stored (or ATATT slug lookup failed above).
        // Try role-based discovery endpoints: member → owner → workspace list fallback.
        List<QuickImportRepoDto> byMember = fetchBitbucketRepoPage(
                "https://api.bitbucket.org/2.0/repositories?role=member&pagelen=100&sort=-updated_on",
                bearerHeader);
        if (!byMember.isEmpty()) return byMember;

        List<QuickImportRepoDto> byOwner = fetchBitbucketRepoPage(
                "https://api.bitbucket.org/2.0/repositories?role=owner&pagelen=100&sort=-updated_on",
                bearerHeader);
        if (!byOwner.isEmpty()) return byOwner;

        // Workspace-list fallback (works when the token has workspace:read scope).
        HttpRequest wsReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.bitbucket.org/2.0/workspaces?pagelen=100"))
                .header("Authorization", bearerHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> wsResp = httpClient.send(wsReq, HttpResponse.BodyHandlers.ofString());
        if (wsResp.statusCode() != 200) {
            log.warn("[RepoBrowser] Bitbucket /workspaces returned HTTP {}", wsResp.statusCode());
            return List.of();
        }
        JsonNode wsRoot = OBJECT_MAPPER.readTree(wsResp.body());
        JsonNode workspaces = wsRoot.path("values");
        if (!workspaces.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode ws : workspaces) {
            String slug = ws.path("slug").asText();
            if (!slug.isBlank()) {
                result.addAll(fetchBitbucketRepoPage(
                        "https://api.bitbucket.org/2.0/repositories/" + slug + "?pagelen=100&sort=-updated_on",
                        bearerHeader));
            }
        }
        return result;
    }

    /** Fetches one page of Bitbucket repos and maps them to DTOs. Returns empty list on non-200. */
    private List<QuickImportRepoDto> fetchBitbucketRepoPage(String url, String authHeader) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        log.info("[RepoBrowser] Bitbucket {} → HTTP {} body-snippet: {}", url,
                resp.statusCode(), resp.body().substring(0, Math.min(200, resp.body().length())));
        if (resp.statusCode() != 200) {
            log.warn("[RepoBrowser] Bitbucket Cloud returned HTTP {} for {}", resp.statusCode(), url);
            return List.of();
        }
        JsonNode root = OBJECT_MAPPER.readTree(resp.body());
        JsonNode values = root.path("values");
        if (!values.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode r : values) {
            String webUrl = r.path("links").path("html").path("href").asText();
            String fullName = r.path("full_name").asText();
            boolean priv = r.path("is_private").asBoolean(false);
            String updatedAt = r.path("updated_on").asText();
            String defaultBranch = r.path("mainbranch").path("name").asText("main");
            result.add(new QuickImportRepoDto(r.path("name").asText(), fullName, webUrl, defaultBranch, priv, updatedAt));
        }
        return result;
    }

    private List<QuickImportRepoDto> listBitbucketServerRepos(String token, String serverUrl) throws Exception {
        String base = serverUrl.replaceAll("/+$", "");
        String url = base + "/rest/api/1.0/repos?limit=100";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("[RepoBrowser] Bitbucket Server returned HTTP {}", resp.statusCode());
            return List.of();
        }

        JsonNode root = OBJECT_MAPPER.readTree(resp.body());
        JsonNode values = root.path("values");
        if (!values.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode r : values) {
            String repoName = r.path("name").asText();
            String projectKey = r.path("project").path("key").asText();
            String slug = r.path("slug").asText();
            String fullName = projectKey + "/" + slug;
            // Construct browse URL from self link
            String webUrl = base + "/projects/" + projectKey + "/repos/" + slug + "/browse";
            boolean priv = !r.path("public").asBoolean(true);
            result.add(new QuickImportRepoDto(repoName, fullName, webUrl, "main", priv, ""));
        }
        return result;
    }



    private void runImport(String jobId, String repoUrl, String branch, Long userId) throws Exception {
        // 1. Parse the URL (pass user connections so self-hosted hosts can be identified) ────
        List<UserVcsConnection> userConns = vcsConnectionRepository.findByUserIdAndActiveTrue(userId);
        ParsedRepoUrl parsed = parseRepoUrl(repoUrl, userConns);
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
                    parsed.provider,
                    parsed.owner, parsed.repo,
                    branch != null && !branch.isBlank() ? branch : "default",
                    userId);

            ApiKeyResult keyResult = getOrIssueApiKey(project);

            // 6. Submit scan ───────────────────────────────
            updateJob(jobId, Phase.SCANNING, "Submitting scan payload (" + deps.components.size() + " components)…",
                    project.getId(), project.getName(), null, false, deps.ecosystem, deps.components.size());

            String scanVersion = branch != null && !branch.isBlank() ? branch : "default";
            ScanPayload payload = buildScanPayload(deps, scanVersion);
            try {
                scanIngestService.ingest(project.getId(), payload, userId);
            } catch (Exception ingestEx) {
                // Project row was already committed; scan could not be saved.
                // The project will appear on the Projects page with a "No scan data" indicator.
                // The user can re-import to retry.
                log.error("[QuickImport][{}] Scan ingest failed for project {}: {}",
                        jobId, project.getId(), ingestEx.getMessage(), ingestEx);
                updateJob(jobId, Phase.FAILED,
                        "Scan submission failed: " + ingestEx.getMessage(),
                        project.getId(), project.getName(),
                        keyResult.token, keyResult.isNew,
                        deps.ecosystem, deps.components.size());
                return;
            }

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

    /**
     * @param clonePath the path segment used in the authenticated clone URL (e.g. {@code /scm/proj/repo}
     *                  for Bitbucket DC/Server, or {@code null} to fall back to {@code /owner/repo}).
     */
    private record ParsedRepoUrl(VcsProvider provider, String host, String owner, String repo, String clonePath) {}

    private ParsedRepoUrl parseRepoUrl(String rawUrl, List<UserVcsConnection> userConnections) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        // Normalize: remove trailing .git and trailing slash
        String url = rawUrl.trim().replaceAll("\\.git$", "").replaceAll("/$", "");
        // Extract host + full path: handle https://host/...
        Pattern hostPattern = Pattern.compile("https?://([^/]+)(/.*)");
        Matcher m = hostPattern.matcher(url);
        if (!m.matches()) return null;
        String host = m.group(1).toLowerCase();
        String path = m.group(2); // starts with /

        // ── Bitbucket Cloud ──────────────────────────────────────────────
        if (host.equals("bitbucket.org") || host.endsWith(".bitbucket.org")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, parts[0], parts[1], null);
        }

        // ── GitHub (cloud + enterprise) ──────────────────────────────────
        if (host.equals("github.com") || host.endsWith(".github.com")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.GITHUB, host, parts[0], parts[1], null);
        }

        // ── GitLab (cloud + self-hosted) ─────────────────────────────────
        if (host.equals("gitlab.com") || host.contains("gitlab")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.GITLAB, host, parts[0], parts[1], null);
        }

        // ── Self-hosted: match against stored connections ─────────────────
        for (UserVcsConnection conn : userConnections) {
            if (conn.getServerUrl() == null || conn.getServerUrl().isBlank()) continue;
            try {
                String connHost = conn.getServerUrl().replaceAll("https?://", "").split("/")[0].toLowerCase();
                if (!connHost.equals(host)) continue;

                if (conn.getProvider() == VcsProvider.BITBUCKET) {
                    // Try /scm/{proj}/{repo} pattern (Bitbucket DC/Server HTTPS clone URL)
                    Matcher scm = Pattern.compile("^/scm/([^/]+)/([^/]+)").matcher(path);
                    if (scm.find()) {
                        String proj = scm.group(1);
                        String repo = scm.group(2);
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, proj, repo, "/scm/" + proj + "/" + repo);
                    }
                    // Try /projects/{PROJ}/repos/{repo} pattern (Bitbucket DC/Server browse URL)
                    Matcher projects = Pattern.compile("^/projects/([^/]+)/repos/([^/]+)").matcher(path);
                    if (projects.find()) {
                        String proj = projects.group(1).toLowerCase();
                        String repo = projects.group(2);
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, proj, repo, "/scm/" + proj + "/" + repo);
                    }
                    // Fallback: first two path segments
                    String[] parts = splitTwoPathSegments(path);
                    if (parts != null) {
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, parts[0], parts[1], null);
                    }
                } else {
                    // GitHub Enterprise or GitLab self-hosted via stored connection
                    String[] parts = splitTwoPathSegments(path);
                    if (parts != null) {
                        return new ParsedRepoUrl(conn.getProvider(), host, parts[0], parts[1], null);
                    }
                }
            } catch (Exception ignored) {}
        }

        log.warn("[QuickImport] Unknown host '{}' — cannot determine VCS provider.", host);
        return null;
    }

    /** Extracts the first two non-empty path segments from a path like {@code /owner/repo/...}. */
    private static String[] splitTwoPathSegments(String path) {
        String[] segs = path.replaceAll("^/+", "").split("/", -1);
        if (segs.length < 2 || segs[0].isBlank() || segs[1].isBlank()) return null;
        return new String[]{ segs[0], segs[1] };
    }

    private String buildAuthUrl(ParsedRepoUrl parsed, String token) {
        String repoPath = parsed.clonePath() != null
                ? parsed.clonePath()
                : "/" + parsed.owner() + "/" + parsed.repo();
        return switch (parsed.provider()) {
            case GITHUB, GITLAB ->
                    "https://oauth2:" + token + "@" + parsed.host() + "/" + parsed.owner() + "/" + parsed.repo() + ".git";
            case BITBUCKET ->
                    "https://x-token-auth:" + token + "@" + parsed.host() + repoPath + ".git";
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
        // Drain output in a virtual thread to prevent pipe-buffer deadlock on large clones
        final String[] outputHolder = {""};
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try { outputHolder[0] = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (IOException ignored) {}
        });
        boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException("git clone timed out after 5 minutes");
        }
        try { outputReader.join(2_000); } catch (InterruptedException ignored) {}
        String output = outputHolder[0];
        int exitCode = proc.exitValue();

        if (exitCode != 0) {
            String safe = output.replaceAll("(https?://)([^@]+@)", "$1***@");
            throw new RuntimeException("git clone failed (exit " + exitCode + "): " + safe.trim());
        }
    }

    // ── Dependency parsing ─────────────────────────────────────────────────

    private record ParsedDependencies(String ecosystem, List<ScanPayload.ComponentPayload> components) {}

    private record GradleComponent(String name, String version, List<List<ScanPayload.DependencyNodeRef>> paths) {}

    private ParsedDependencies parseDependencies(Path cloneDir, String repoName) {
        // Detect by presence of build files (ordered by priority)
        if (Files.exists(cloneDir.resolve("pom.xml"))) {
            return parseMaven(cloneDir, repoName);
        }
        // Gradle before npm — a Gradle project might have a package.json/lock file for frontend tooling
        if (Files.exists(cloneDir.resolve("build.gradle")) ||
                Files.exists(cloneDir.resolve("build.gradle.kts"))) {
            return parseGradle(cloneDir, repoName);
        }
        if (Files.exists(cloneDir.resolve("package.json")) ||
                Files.exists(cloneDir.resolve("package-lock.json")) ||
                Files.exists(cloneDir.resolve("yarn.lock")) ||
                Files.exists(cloneDir.resolve("pnpm-lock.yaml"))) {
            return parseNpm(cloneDir, repoName);
        }
        // Python — check lock files first (full transitive), then requirements.txt
        if (Files.exists(cloneDir.resolve("poetry.lock")) ||
                Files.exists(cloneDir.resolve("Pipfile.lock")) ||
                Files.exists(cloneDir.resolve("uv.lock")) ||
                Files.exists(cloneDir.resolve("requirements.txt"))) {
            return parsePython(cloneDir, repoName);
        }
        // Cargo — Cargo.lock has full transitive tree
        if (Files.exists(cloneDir.resolve("Cargo.toml")) ||
                Files.exists(cloneDir.resolve("Cargo.lock"))) {
            return parseCargo(cloneDir, repoName);
        }
        // Go — go.sum has full transitive list
        if (Files.exists(cloneDir.resolve("go.mod")) ||
                Files.exists(cloneDir.resolve("go.sum"))) {
            return parseGoMod(cloneDir, repoName);
        }
        // NuGet / .NET
        if (Files.exists(cloneDir.resolve("packages.lock.json")) ||
                Files.exists(cloneDir.resolve("packages.config")) ||
                hasCsprojFiles(cloneDir)) {
            return parseNuGet(cloneDir, repoName);
        }
        // Ruby / RubyGems
        if (Files.exists(cloneDir.resolve("Gemfile.lock")) ||
                Files.exists(cloneDir.resolve("Gemfile"))) {
            return parseRuby(cloneDir, repoName);
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
            Element project = doc.getDocumentElement();

            // Build property map for ${prop} resolution
            Map<String, String> props = new HashMap<>();
            String projectVersion = getDirectChildText(project, "version");
            String parentVersion = null;
            Element parent = getDirectChild(project, "parent");
            if (parent != null) {
                parentVersion = getDirectChildText(parent, "version");
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

            // Only walk direct /project/dependencies/dependency (skip dependencyManagement,
            // <build>/<plugin> deps, profiles).
            Element depsRoot = getDirectChild(project, "dependencies");
            if (depsRoot == null) {
                log.info("[QuickImport][Maven] No <dependencies> in pom.xml of '{}'", repoName);
                return new ParsedDependencies("MAVEN", comps);
            }
            NodeList deps = depsRoot.getChildNodes();
            for (int i = 0; i < deps.getLength(); i++) {
                if (!(deps.item(i) instanceof Element dep) || !"dependency".equals(dep.getTagName())) continue;
                String groupId    = resolveProp(getDirectChildText(dep, "groupId"), props);
                String artifactId = resolveProp(getDirectChildText(dep, "artifactId"), props);
                String version    = resolveProp(getDirectChildText(dep, "version"), props);
                String scope      = getDirectChildText(dep, "scope");

                if (groupId == null || artifactId == null) continue;
                // Skip test/provided/system scopes for the primary scan (align with CLI)
                if ("test".equalsIgnoreCase(scope) || "system".equalsIgnoreCase(scope)
                        || "provided".equalsIgnoreCase(scope)) continue;

                comps.add(buildComponent(groupId + ":" + artifactId, version, "MAVEN"));
            }
            log.info("[QuickImport][Maven] Parsed {} components from pom.xml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Maven] Failed to parse pom.xml: {}", e.getMessage());
        }
        return new ParsedDependencies("MAVEN", comps);
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

    // npm: try package-lock.json first (full transitive tree), then yarn.lock, then pnpm-lock.yaml,
    // finally fall back to package.json declared deps only.
    private ParsedDependencies parseNpm(Path dir, String repoName) {
        if (Files.exists(dir.resolve("package-lock.json"))) {
            List<ScanPayload.ComponentPayload> locked = parseNpmLock(dir, repoName);
            if (locked != null && !locked.isEmpty()) {
                return new ParsedDependencies("NPM", locked);
            }
        }
        if (Files.exists(dir.resolve("yarn.lock"))) {
            List<ScanPayload.ComponentPayload> yarn = parseYarnLock(dir, repoName);
            if (yarn != null && !yarn.isEmpty()) {
                return new ParsedDependencies("NPM", yarn);
            }
        }
        if (Files.exists(dir.resolve("pnpm-lock.yaml"))) {
            List<ScanPayload.ComponentPayload> pnpm = parsePnpmLock(dir, repoName);
            if (pnpm != null && !pnpm.isEmpty()) {
                return new ParsedDependencies("NPM", pnpm);
            }
        }
        return parseNpmPackageJson(dir, repoName);
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
                Deque<Map.Entry<String, JsonNode>> queue = new ArrayDeque<>();
                root.path("dependencies").properties().forEach(queue::add);
                while (!queue.isEmpty()) {
                    Map.Entry<String, JsonNode> entry = queue.poll();
                    String name    = entry.getKey();
                    JsonNode val   = entry.getValue();
                    String version = val.path("version").asText(null);
                    if (version != null && !version.isBlank() && seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                    if (val.has("dependencies")) {
                        val.path("dependencies").properties().forEach(queue::add);
                    }
                }
            }
            log.info("[QuickImport][npm] Parsed {} components from package-lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][npm] Failed to parse package-lock.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /** Fallback: parse package.json declared deps only (no transitive). */
    private ParsedDependencies parseNpmPackageJson(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package.json").toFile());
            JsonNode deps    = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");

            addNpmDeps(comps, deps);
            addNpmDeps(comps, devDeps);
            log.info("[QuickImport][npm] Parsed {} components from package.json in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][npm] Failed to parse package.json: {}", e.getMessage());
        }
        return new ParsedDependencies("NPM", comps);
    }

    private void addNpmDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode) {
        if (depsNode == null || depsNode.isMissingNode()) return;
        depsNode.properties().forEach(entry -> {
            String name    = entry.getKey();
            String version = entry.getValue().asText().replaceAll("^[~^>=<]+ *", "");
            comps.add(buildComponent(name, version, "NPM"));
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
            log.info("[QuickImport][npm] Parsed {} components from yarn.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][npm] Failed to parse yarn.lock for '{}': {}", repoName, e.getMessage());
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
            log.info("[QuickImport][npm] Parsed {} components from pnpm-lock.yaml in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][npm] Failed to parse pnpm-lock.yaml for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    // Gradle: run gradlew dependencies for full transitive resolution, fall back to static parse
    private ParsedDependencies parseGradle(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> resolved = runGradleDependencies(dir, repoName);
        if (resolved != null && !resolved.isEmpty()) {
            return new ParsedDependencies("MAVEN", resolved);
        }
        return parseGradleStatic(dir, repoName);
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
            log.info("[QuickImport][Gradle] No gradlew found in '{}', falling back to static parse", repoName);
            return null;
        }
        try {
            if (!isWindows) {
                wrapper.toFile().setExecutable(true, false);
            }
            List<String> cmd = isWindows
                    ? List.of("cmd", "/c", wrapper.toString(), "dependencies",
                              "--configuration", "runtimeClasspath", "-q", "--no-daemon")
                    : List.of(wrapper.toString(), "dependencies",
                              "--configuration", "runtimeClasspath", "-q", "--no-daemon");

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(dir.toFile())
                    .redirectErrorStream(true);

            // Propagate the server JVM's java.home so gradlew can locate Java
            String javaHome = System.getProperty("java.home");
            if (javaHome != null && !javaHome.isBlank()) {
                pb.environment().put("JAVA_HOME", javaHome);
                String pathSep = isWindows ? ";" : ":";
                String javaBin = javaHome + File.separator + "bin";
                pb.environment().merge("PATH", javaBin,
                        (existing, added) -> added + pathSep + existing);
            }

            log.info("[QuickImport][Gradle] Running gradlew dependencies for '{}'", repoName);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                log.warn("[QuickImport][Gradle] gradlew timed out for '{}', falling back to static parse", repoName);
                return null;
            }
            if (proc.exitValue() != 0) {
                log.warn("[QuickImport][Gradle] gradlew exited {} for '{}', falling back to static parse",
                        proc.exitValue(), repoName);
                return null;
            }
            List<ScanPayload.ComponentPayload> comps = parseGradleTreeOutput(output, repoName);
            log.info("[QuickImport][Gradle] gradlew resolved {} components for '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][Gradle] Failed to run gradlew for '{}': {}", repoName, e.getMessage());
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
            path.add(ReflectiveComponentBuilder.buildNodeRef(projectName, "local"));
            for (int lvl = 0; lvl < depth; lvl++) {
                String[] anc = depStack.get(lvl);
                if (anc != null) path.add(ReflectiveComponentBuilder.buildNodeRef(anc[0], anc[1]));
            }
            path.add(ReflectiveComponentBuilder.buildNodeRef(compName, compVer));

            String key = compName + ":" + (compVer != null ? compVer : "");
            depComps.computeIfAbsent(key, k -> new GradleComponent(compName, compVer, new ArrayList<>()))
                    .paths().add(path);
        }

        List<ScanPayload.ComponentPayload> result = new ArrayList<>();
        for (GradleComponent gc : depComps.values()) {
            boolean hasDirect = gc.paths().stream().anyMatch(p -> p.size() == 2);
            boolean hasTransitive = gc.paths().stream().anyMatch(p -> p.size() > 2);
            String info = hasDirect && hasTransitive ? "Direct + Transitive"
                    : hasDirect ? "Direct" : "Transitive";
            result.add(ReflectiveComponentBuilder.buildWithPaths(
                    gc.name(), gc.version(), "MAVEN", info, gc.paths()));
        }
        return result;
    }

    /** Static fallback: regex-parse all build.gradle files in the repo (no subprocess). */
    private ParsedDependencies parseGradleStatic(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<Path> buildFiles;
            try (Stream<Path> stream = Files.walk(dir)) {
                buildFiles = stream
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            if (!name.equals("build.gradle") && !name.equals("build.gradle.kts")) return false;
                            String rel = dir.relativize(p).toString();
                            return !rel.contains(File.separator + "build" + File.separator)
                                    && !rel.contains(File.separator + ".gradle" + File.separator)
                                    && !rel.startsWith("build" + File.separator)
                                    && !rel.startsWith(".gradle" + File.separator);
                        })
                        .toList();
            }

            // Matches Groovy DSL and Kotlin DSL, with or without explicit version.
            // Configurations: standard JVM, Kotlin (kapt/ksp), Android variants, custom *Implementation.
            Pattern p = Pattern.compile(
                    "(?:implementation|api|runtimeOnly|compileOnly|annotationProcessor|" +
                    "testImplementation|testRuntimeOnly|testCompileOnly|" +
                    "kapt|ksp|developmentOnly|" +
                    "(?:androidTest|debug|release)Implementation|" +
                    "[A-Za-z][A-Za-z0-9_]*Implementation)" +
                    "\\s*\\(?[\"']([\\w.\\-]+:[\\w.\\-]+)(?::([\\w.+\\-]+))?[\"']",
                    Pattern.CASE_INSENSITIVE);

            for (Path buildFile : buildFiles) {
                String content = Files.readString(buildFile, StandardCharsets.UTF_8);
                Matcher m = p.matcher(content);
                while (m.find()) {
                    String key     = m.group(1);
                    String version = m.group(2);
                    if (seen.add(key)) {
                        comps.add(buildComponent(key, version, "MAVEN"));
                    }
                }
            }
            log.info("[QuickImport][Gradle] Static-parsed {} components from {} build file(s) in '{}'",
                    comps.size(), buildFiles.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Gradle] Failed to static-parse build.gradle: {}", e.getMessage());
        }
        return new ParsedDependencies("MAVEN", comps);
    }

    // Python: try lock files first (full transitive), fall back to requirements.txt
    private ParsedDependencies parsePython(Path dir, String repoName) {
        // poetry.lock — [[package]] TOML blocks with all transitive deps
        if (Files.exists(dir.resolve("poetry.lock"))) {
            List<ScanPayload.ComponentPayload> comps = parseTomlPackageLock(dir.resolve("poetry.lock"), "PYPI", repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("PYPI", comps);
        }
        // uv.lock — same [[package]] format
        if (Files.exists(dir.resolve("uv.lock"))) {
            List<ScanPayload.ComponentPayload> comps = parseTomlPackageLock(dir.resolve("uv.lock"), "PYPI", repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("PYPI", comps);
        }
        // Pipfile.lock — JSON with default/develop sections
        if (Files.exists(dir.resolve("Pipfile.lock"))) {
            List<ScanPayload.ComponentPayload> comps = parsePipfileLock(dir, repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("PYPI", comps);
        }
        // requirements.txt fallback
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("requirements.txt"), StandardCharsets.UTF_8);
            // PEP 440 package name (with optional extras) followed by an optional version specifier.
            // The version starts with a digit and stops at whitespace/comma/semicolon/'#'.
            // Lines like "git+https://..." or "./local-pkg" are rejected (name must be just \w/./- chars).
            Pattern fullForm = Pattern.compile(
                    "^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*(===|==|>=|<=|~=|!=|<|>)\\s*([0-9][^\\s,;#]*)");
            Pattern bareName = Pattern.compile("^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*$");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) continue;
                // Strip inline comment, then PEP 508 environment marker after ';'
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash).trim();
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi).trim();
                if (line.isEmpty()) continue;
                String name = null, version = null;
                Matcher m = fullForm.matcher(line);
                if (m.find()) {
                    name = m.group(1);
                    version = m.group(3);
                } else {
                    Matcher b = bareName.matcher(line);
                    if (b.matches()) name = b.group(1);
                }
                if (name == null || name.isBlank()) continue;
                if (seen.add(name + ":" + (version != null ? version : ""))) {
                    comps.add(buildComponent(name, version, "PYPI"));
                }
            }
            log.info("[QuickImport][Python] Parsed {} components from requirements.txt in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Python] Failed to parse requirements.txt: {}", e.getMessage());
        }
        return new ParsedDependencies("PYPI", comps);
    }

    // Cargo (Rust): try Cargo.lock (full transitive), fall back to static Cargo.toml
    private ParsedDependencies parseCargo(Path dir, String repoName) {
        if (Files.exists(dir.resolve("Cargo.lock"))) {
            List<ScanPayload.ComponentPayload> comps = parseTomlPackageLock(dir.resolve("Cargo.lock"), "CARGO", repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("CARGO", comps);
        }
        // Static Cargo.toml fallback — handles inline string form and table form:
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
            log.info("[QuickImport][Cargo] Parsed {} components from Cargo.toml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Cargo] Failed to parse Cargo.toml: {}", e.getMessage());
        }
        return new ParsedDependencies("CARGO", comps);
    }

    // Go modules: try go.sum (full transitive), fall back to go.mod
    private ParsedDependencies parseGoMod(Path dir, String repoName) {
        if (Files.exists(dir.resolve("go.sum"))) {
            List<ScanPayload.ComponentPayload> comps = parseGoSum(dir, repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("GO", comps);
        }
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
                if (t.startsWith("require (") || t.equals("require (")) { inRequire = true; continue; }
                if (inRequire && t.equals(")"))                         { inRequire = false; continue; }
                Matcher m = inRequire ? entry.matcher(t) : single.matcher(t);
                if (m.find() && seen.add(m.group(1))) {
                    comps.add(buildComponent(m.group(1), m.group(2), "GO"));
                }
            }
            log.info("[QuickImport][Go] Parsed {} components from go.mod in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Go] Failed to parse go.mod: {}", e.getMessage());
        }
        return new ParsedDependencies("GO", comps);
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
            log.info("[QuickImport][{}] Parsed {} components from {} in '{}'",
                    ecosystem, comps.size(), lockFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport] Failed to parse {}: {}", lockFile.getFileName(), e.getMessage());
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
                deps.properties().forEach(e -> {
                    String name = e.getKey();
                    String ver  = e.getValue().path("version").asText("").replaceAll("^==", "");
                    if (!name.isBlank() && !ver.isBlank() && seen.add(name + ":" + ver)) {
                        comps.add(buildComponent(name, ver, "PYPI"));
                    }
                });
            }
            log.info("[QuickImport][Python] Parsed {} components from Pipfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][Python] Failed to parse Pipfile.lock: {}", e.getMessage());
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
            log.info("[QuickImport][Go] Parsed {} components from go.sum in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][Go] Failed to parse go.sum: {}", e.getMessage());
            return null;
        }
    }

    // NuGet / .NET
    private ParsedDependencies parseNuGet(Path dir, String repoName) {
        if (Files.exists(dir.resolve("packages.lock.json"))) {
            List<ScanPayload.ComponentPayload> comps = parseNuGetLockFile(dir, repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("NUGET", comps);
        }
        return parseNuGetStatic(dir, repoName);
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
            log.info("[QuickImport][NuGet] Parsed {} components from packages.lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][NuGet] Failed to parse packages.lock.json: {}", e.getMessage());
            return null;
        }
    }

    private ParsedDependencies parseNuGetStatic(Path dir, String repoName) {
        Set<String> seen = new LinkedHashSet<>();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            List<Path> targets = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir, 3)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".csproj")
                              || p.getFileName().toString().equals("packages.config"))
                    .limit(20).forEach(targets::add);
            }
            for (Path f : targets) {
                try {
                    Document doc = dbf.newDocumentBuilder().parse(f.toFile());
                    boolean isPkgConfig = f.getFileName().toString().equals("packages.config");
                    NodeList refs = doc.getElementsByTagName(isPkgConfig ? "package" : "PackageReference");
                    for (int i = 0; i < refs.getLength(); i++) {
                        Element ref = (Element) refs.item(i);
                        String name = ref.hasAttribute("Include") ? ref.getAttribute("Include") : ref.getAttribute("id");
                        String ver  = ref.hasAttribute("Version") ? ref.getAttribute("Version") : ref.getAttribute("version");
                        if (!name.isBlank() && seen.add(name)) {
                            comps.add(buildComponent(name, ver.isBlank() ? null : ver, "NUGET"));
                        }
                    }
                } catch (Exception ignored) {}
            }
            log.info("[QuickImport][NuGet] Parsed {} components from {} file(s) in '{}'",
                    comps.size(), targets.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][NuGet] Failed to parse .csproj/packages.config: {}", e.getMessage());
        }
        return new ParsedDependencies("NUGET", comps);
    }

    private boolean hasCsprojFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.getFileName().toString().endsWith(".csproj"));
        } catch (IOException e) {
            return false;
        }
    }

    // Ruby / RubyGems
    private ParsedDependencies parseRuby(Path dir, String repoName) {
        if (Files.exists(dir.resolve("Gemfile.lock"))) {
            List<ScanPayload.ComponentPayload> comps = parseGemfileLock(dir, repoName);
            if (comps != null && !comps.isEmpty()) return new ParsedDependencies("RUBYGEMS", comps);
        }
        log.warn("[QuickImport][Ruby] No Gemfile.lock found in '{}', cannot resolve transitive dependencies", repoName);
        return new ParsedDependencies("RUBYGEMS", List.of());
    }

    private List<ScanPayload.ComponentPayload> parseGemfileLock(Path dir, String repoName) {
        try {
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            boolean inSpecs = false;
            Pattern specLine = Pattern.compile("^    ([A-Za-z0-9_\\-][^\\s(]*)\\s+\\(([0-9][^)]*)\\)");
            for (String line : Files.readAllLines(dir.resolve("Gemfile.lock"), StandardCharsets.UTF_8)) {
                if (line.equals("  specs:")) { inSpecs = true; continue; }
                if (inSpecs && !line.startsWith(" ")) { inSpecs = false; continue; }
                if (inSpecs) {
                    Matcher m = specLine.matcher(line);
                    if (m.find() && seen.add(m.group(1))) {
                        comps.add(buildComponent(m.group(1), m.group(2), "RUBYGEMS"));
                    }
                }
            }
            log.info("[QuickImport][Ruby] Parsed {} components from Gemfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[QuickImport][Ruby] Failed to parse Gemfile.lock: {}", e.getMessage());
            return null;
        }
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
     * labeled "quick-import".
     */
    private ApiKeyResult getOrIssueApiKey(Project project) {
        List<ApiKey> existing = apiKeyService.findByProject(project.getId())
                .stream()
                .filter(ApiKey::isValid)
                .toList();
        if (!existing.isEmpty()) {
            return new ApiKeyResult(existing.getFirst().getToken(), false);
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
        try (Stream<Path> stream = Files.walk(dir)){
            stream
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException e) {
            log.warn("[QuickImport] Could not delete temp dir '{}': {}", dir, e.getMessage());
        }
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

    /** Removes jobs older than 30 minutes from memory. Runs every 5 minutes. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        jobCreatedAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                jobs.remove(entry.getKey());
                log.debug("[QuickImport] Evicted expired job {}", entry.getKey());
                return true;
            }
            return false;
        });
    }

    // ── Reflective builder helper ─────────────────────────────────────────

    /**
     * {@link ScanPayload.ComponentPayload} has only a no-arg constructor and
     * package-private fields set via Jackson deserialization (no setters).
     * We use field access to populate instances programmatically.
     */
    private static final class ReflectiveComponentBuilder {

        static ScanPayload.ComponentPayload build(String name, String version, String ecosystem) {
            return buildWithPaths(name, version, ecosystem, "Direct", List.of());
        }

        static ScanPayload.ComponentPayload buildWithPaths(String name, String version, String ecosystem,
                String dependencyInfo, List<List<ScanPayload.DependencyNodeRef>> paths) {
            try {
                ScanPayload.ComponentPayload c = ScanPayload.ComponentPayload.class.getDeclaredConstructor().newInstance();
                setField(c, "name", name);
                setField(c, "version", version);
                setField(c, "ecosystem", ecosystem);
                setField(c, "dependencyInfo", dependencyInfo);
                setField(c, "dependencyPaths", paths);
                return c;
            } catch (Exception e) {
                throw new RuntimeException("Failed to build ComponentPayload", e);
            }
        }

        static ScanPayload.DependencyNodeRef buildNodeRef(String name, String version) {
            try {
                ScanPayload.DependencyNodeRef ref =
                        ScanPayload.DependencyNodeRef.class.getDeclaredConstructor().newInstance();
                setField(ref, "name", name);
                setField(ref, "version", version);
                return ref;
            } catch (Exception e) {
                throw new RuntimeException("Failed to build DependencyNodeRef", e);
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
