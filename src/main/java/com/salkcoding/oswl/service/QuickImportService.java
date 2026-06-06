package com.salkcoding.oswl.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.BitbucketCloudClient;
import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import com.salkcoding.oswl.service.git.GitCloneCredentials;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus.Phase;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
 * HTTP response is immediate; the UI polls {@link #getJobStatus(String, Long)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickImportService {

    private final MavenBomVersionResolver bomVersionResolver;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "OsWL-App/1.0";
    /** Directory name segments to prune when recursively walking for build manifests. */
    private static final Set<String> MANIFEST_SKIP_DIRS = Set.of(
            ".git", "node_modules", "vendor", "target", ".gradle", "__pycache__",
            ".venv", "venv", ".tox", "dist", ".cargo", "build", "out", "bin",
            "obj", ".idea", ".dart_tool", "bower_components", "generated");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${oswl.clone.temp-dir:}")
    private String configuredTempDir;

    @Value("${oswl.quick-import.max-concurrent:2}")
    private int maxConcurrentImports;

    private volatile Path cloneBaseReal;

    private final GitCloneExecutor gitCloneExecutor;
    private final UserVcsConnectionRepository vcsConnectionRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final EncryptionService encryptionService;
    private final ProjectService projectService;
    private final ApiKeyService apiKeyService;
    private final ScanIngestService scanIngestService;
    private final ScanResultRepository scanResultRepository;
    private final GitHubService gitHubService;
    private final BitbucketCloudClient bitbucketCloudClient;
    private final EnrichmentProgressHolder enrichmentProgressHolder;
    private final ProjectCliKeyPolicyService projectCliKeyPolicyService;
    private final MessageSource messageSource;

    /** In-memory job tracker. Entries are removed after 30 minutes by {@link #evictExpiredJobs()}. */
    private final ConcurrentHashMap<String, QuickImportJobStatus> jobs = new ConcurrentHashMap<>();
    /** Tracks job creation times for TTL-based eviction. */
    private final ConcurrentHashMap<String, Instant> jobCreatedAt = new ConcurrentHashMap<>();
    /** Job owner — used to prevent cross-user status polling (IDOR). */
    private final ConcurrentHashMap<String, Long> jobOwners = new ConcurrentHashMap<>();
    /** Tracks whether the full API token was already returned once on DONE. */
    private final ConcurrentHashMap<String, Boolean> apiTokenRevealed = new ConcurrentHashMap<>();
    /** Per-user job IDs (most recent last) for multi-import UI. */
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<String>> userJobIds = new ConcurrentHashMap<>();
    /** FIFO queue waiting for a worker slot. */
    private final Deque<PendingImport> pendingQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final AtomicInteger runningImports = new AtomicInteger(0);
    private final Object dispatchLock = new Object();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> jobEmitters = new ConcurrentHashMap<>();

    private record PendingImport(String jobId, String repoUrl, String branch, Long userId, String repoLabel) {}

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
        String repoLabel = extractRepoLabel(repoUrl);
        int queuePosition = pendingQueue.size() + runningImports.get() + 1;

        jobs.put(jobId, baseJobBuilder(jobId)
                .phase(Phase.QUEUED)
                .message(queuedMessage(queuePosition))
                .repoLabel(repoLabel)
                .queuePosition(queuePosition)
                .percent(0)
                .build());
        jobCreatedAt.put(jobId, Instant.now());
        jobOwners.put(jobId, userId);
        userJobIds.computeIfAbsent(userId, id -> new CopyOnWriteArrayList<>()).add(jobId);
        notifyJobUpdate(jobId);

        log.info("[QuickImport][{}] Job queued userId={} repo={} queuePos={}", jobId, userId,
                repoUrl.replaceAll("(https?://)([^@/]+@)", "$1***@"), queuePosition);

        String safeRepo = repoUrl.replaceAll("(https?://)([^@/]+@)", "$1***@");
        auditLogService.log("QUICK_IMPORT.START", "PROJECT", null, safeRepo,
                "jobId=" + jobId + " branch=" + (branch != null && !branch.isBlank() ? branch : "default"));

        pendingQueue.offer(new PendingImport(jobId, repoUrl, branch, userId, repoLabel));
        refreshQueuePositions();
        dispatchQueue();

        return jobId;
    }

    public List<QuickImportJobStatus> listJobsForUser(Long userId) {
        CopyOnWriteArrayList<String> ids = userJobIds.get(userId);
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(id -> sanitizeApiToken(id, resolveJobStatus(id)))
                .filter(Objects::nonNull)
                .toList();
    }

    public SseEmitter subscribeJobStream(String jobId, Long userId) {
        if (!isJobOwner(jobId, userId)) {
            throw new IllegalArgumentException("Job not found");
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        jobEmitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(jobId, emitter));
        emitter.onTimeout(() -> removeEmitter(jobId, emitter));
        emitter.onError(e -> removeEmitter(jobId, emitter));
        try {
            // SSE is owner-scoped; do not consume the one-time HTTP reveal slot or mask the token here.
            QuickImportJobStatus status = resolveJobStatus(jobId);
            emitter.send(SseEmitter.event().name("job-update").data(status, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void dispatchQueue() {
        synchronized (dispatchLock) {
            while (runningImports.get() < maxConcurrentImports && !pendingQueue.isEmpty()) {
                PendingImport task = pendingQueue.poll();
                if (task == null) break;
                runningImports.incrementAndGet();
                refreshQueuePositions();
                Thread.ofVirtual().name("quick-import-" + task.jobId()).start(() -> executeImport(task));
            }
        }
    }

    private void executeImport(PendingImport task) {
        String jobId = task.jobId();
        try {
            patchJob(jobId, b -> b.phase(Phase.CLONING)
                    .message("Starting " + task.repoLabel() + "…")
                    .queuePosition(null)
                    .percent(phasePercent(Phase.CLONING, null)));
            runImport(jobId, task.repoUrl(), task.branch(), task.userId());
        } catch (com.salkcoding.oswl.exception.ConflictException e) {
            log.warn("[QuickImport][{}] Blocked by CLI key policy: {}", jobId, e.getMessage());
            updateJob(jobId, Phase.FAILED, e.getMessage(), null, null, null, null, null, null);
        } catch (Throwable t) {
            log.error("[QuickImport][{}] Unhandled error", jobId, t);
            String msg = messageSource.getMessage("quickImport.error.unexpected",
                    null, LocaleContextHolder.getLocale());
            updateJob(jobId, Phase.FAILED, msg, null, null, null, null, null, null);
        } finally {
            runningImports.decrementAndGet();
            refreshQueuePositions();
            dispatchQueue();
        }
    }

    /**
     * Returns the current status of a job for the requesting user, or null if the job is unknown
     * or not owned by that user.
     */
    public QuickImportJobStatus getJobStatus(String jobId, Long requestingUserId) {
        if (requestingUserId == null || !isJobOwner(jobId, requestingUserId)) {
            return null;
        }
        return sanitizeApiToken(jobId, resolveJobStatus(jobId));
    }

    private boolean isJobOwner(String jobId, Long userId) {
        Long owner = jobOwners.get(jobId);
        return owner != null && owner.equals(userId);
    }

    private QuickImportJobStatus resolveJobStatus(String jobId) {
        QuickImportJobStatus job = jobs.get(jobId);
        if (job == null) return null;

        final QuickImportJobStatus current = job.toBuilder()
                .activeSlotsUsed(runningImports.get())
                .maxConcurrentSlots(maxConcurrentImports)
                .build();

        if (current.getPhase() == Phase.ENRICHING && current.getScanResultId() != null) {
            EnrichmentProgressHolder.Snapshot enrich =
                    enrichmentProgressHolder.getSnapshot(current.getScanResultId());
            return scanResultRepository.findById(current.getScanResultId())
                    .map(sr -> {
                        if (sr.getStatus() == ScanStatus.COMPLETED) {
                            QuickImportJobStatus done = current.toBuilder()
                                    .phase(Phase.DONE)
                                    .message("Import complete \u2014 " + current.getComponentCount() + " components scanned.")
                                    .percent(100)
                                    .queuePosition(null)
                                    .subPhase(null)
                                    .build();
                            jobs.put(jobId, done);
                            notifyJobUpdate(jobId);
                            return done;
                        } else if (sr.getStatus() == ScanStatus.FAILED) {
                            QuickImportJobStatus failed = current.toBuilder()
                                    .phase(Phase.FAILED)
                                    .message("Enrichment failed.")
                                    .error("Enrichment pipeline failed.")
                                    .build();
                            jobs.put(jobId, failed);
                            notifyJobUpdate(jobId);
                            return failed;
                        }
                        QuickImportJobStatus.QuickImportJobStatusBuilder enriching = current.toBuilder();
                        if (enrich != null) {
                            enriching.message(enrich.message())
                                    .subPhase(enrich.subPhase() != null ? enrich.subPhase().name() : null)
                                    .detailLines(enrich.detailLines())
                                    .aiPreviews(enrich.aiPreviews())
                                    .percent(enrichingPercent(enrich.percent()));
                        }
                        QuickImportJobStatus live = enriching.build();
                        jobs.put(jobId, live);
                        return live;
                    })
                    .orElse(current);
        }
        return current;
    }

    private static int enrichingPercent(int enrichStepPercent) {
        return 60 + (enrichStepPercent * 35 / 100);
    }

    /**
     * Returns the API token in full only on the first DONE poll; subsequent polls receive a masked value.
     */
    private QuickImportJobStatus sanitizeApiToken(String jobId, QuickImportJobStatus job) {
        if (job == null || job.getApiToken() == null || job.getApiToken().isBlank()) {
            return job;
        }
        if (job.getPhase() == Phase.DONE && !Boolean.TRUE.equals(apiTokenRevealed.get(jobId))) {
            apiTokenRevealed.put(jobId, true);
            return job;
        }
        return job.toBuilder().apiToken(maskApiToken(job.getApiToken())).build();
    }

    static String maskApiToken(String token) {
        if (token == null || token.length() < 12) {
            return null;
        }
        return token.substring(0, 8) + "…" + token.substring(token.length() - 4);
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
        Optional<UserVcsConnection> connOpt =
                vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(userId, provider);

        if (connOpt.isEmpty()) return List.of();

        UserVcsConnection conn = connOpt.get();
        String token;
        try {
            token = encryptionService.decrypt(conn.getAccessTokenEncrypted());
        } catch (Exception e) {
            log.warn("[RepoBrowser] Failed to decrypt token for provider {}: {}", provider, e.getMessage());
            String msg = messageSource.getMessage("quickImport.error.tokenDecrypt",
                    null, LocaleContextHolder.getLocale());
            throw new com.salkcoding.oswl.exception.QuickImportUpstreamException(msg);
        }

        try {
            return switch (provider) {
                case GITHUB   -> listGitHubRepos(token, conn.getServerUrl());
                case GITLAB   -> listGitLabRepos(token, conn.getServerUrl());
                case BITBUCKET -> isBitbucketCloud(conn.getServerUrl())
                        ? listBitbucketCloudRepos(token, conn.getVcsUsername())
                        : listBitbucketServerRepos(token, conn.getServerUrl());
            };
        } catch (com.salkcoding.oswl.exception.OutboundUrlBlockedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RepoBrowser] Failed to list repos for provider {}: {}", provider, e.toString());
            throw new com.salkcoding.oswl.exception.QuickImportUpstreamException(
                    "Could not load repositories from the VCS provider.");
        }
    }

    private List<QuickImportRepoDto> listGitHubRepos(String token, String serverUrl) {
        String webBase = gitHubService.resolveWebBase(serverUrl);
        return gitHubService.listAllUserRepos(token, serverUrl).stream()
                .map(r -> new QuickImportRepoDto(
                        r.getName(),
                        r.getFullName(),
                        webBase + "/" + r.getFullName(),
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

    /** True when serverUrl is blank or points at Bitbucket Cloud (not Data Center). */
    private static boolean isBitbucketCloud(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) return true;
        String normalized = serverUrl.trim().replaceAll("/+$", "").toLowerCase();
        return normalized.equals("https://bitbucket.org")
                || normalized.equals("http://bitbucket.org");
    }

    private List<QuickImportRepoDto> listBitbucketCloudRepos(String tokenOrAppPassword, String username) throws Exception {
        return bitbucketCloudClient.listRepositories(username, tokenOrAppPassword);
    }

    private List<QuickImportRepoDto> listBitbucketServerRepos(String token, String serverUrl) throws Exception {
        String base = serverUrl.replaceAll("/+$", "");
        String authHeader = "Bearer " + token;

        // Primary: /rest/api/1.0/projects → repos per project (Data Center standard)
        List<QuickImportRepoDto> fromProjects = listServerReposViaProjects(base, authHeader);
        if (!fromProjects.isEmpty()) return fromProjects;

        // Fallback: global repo list
        String url = base + "/rest/api/1.0/repos?limit=100";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            log.warn("[RepoBrowser] Bitbucket Server returned HTTP {} for {}", resp.statusCode(), url);
            return List.of();
        }

        JsonNode root = OBJECT_MAPPER.readTree(resp.body());
        JsonNode values = root.path("values");
        if (!values.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode r : values) {
            result.add(mapServerRepoNode(r, base));
        }
        return result;
    }

    private List<QuickImportRepoDto> listServerReposViaProjects(String base, String authHeader) throws Exception {
        HttpRequest projectsReq = HttpRequest.newBuilder()
                .uri(URI.create(base + "/rest/api/1.0/projects?limit=100"))
                .header("Authorization", authHeader)
                .header("User-Agent", USER_AGENT)
                .GET().build();
        HttpResponse<String> projectsResp = httpClient.send(projectsReq, HttpResponse.BodyHandlers.ofString());
        if (projectsResp.statusCode() != 200) {
            log.warn("[RepoBrowser] Bitbucket Server /projects returned HTTP {}", projectsResp.statusCode());
            return List.of();
        }

        JsonNode projectsRoot = OBJECT_MAPPER.readTree(projectsResp.body());
        JsonNode projects = projectsRoot.path("values");
        if (!projects.isArray()) return List.of();

        List<QuickImportRepoDto> result = new ArrayList<>();
        for (JsonNode project : projects) {
            String projectKey = project.path("key").asText();
            if (projectKey.isBlank()) continue;

            HttpRequest reposReq = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/rest/api/1.0/projects/" + projectKey + "/repos?limit=100"))
                    .header("Authorization", authHeader)
                    .header("User-Agent", USER_AGENT)
                    .GET().build();
            HttpResponse<String> reposResp = httpClient.send(reposReq, HttpResponse.BodyHandlers.ofString());
            if (reposResp.statusCode() != 200) {
                log.warn("[RepoBrowser] Bitbucket Server /projects/{}/repos returned HTTP {}",
                        projectKey, reposResp.statusCode());
                continue;
            }
            JsonNode reposRoot = OBJECT_MAPPER.readTree(reposResp.body());
            JsonNode repos = reposRoot.path("values");
            if (!repos.isArray()) continue;
            for (JsonNode r : repos) {
                result.add(mapServerRepoNode(r, base));
            }
        }
        return result;
    }

    private QuickImportRepoDto mapServerRepoNode(JsonNode r, String base) {
        String repoName = r.path("name").asText();
        String projectKey = r.path("project").path("key").asText();
        String slug = r.path("slug").asText();
        String fullName = projectKey + "/" + slug;
        String webUrl = base + "/projects/" + projectKey + "/repos/" + slug + "/browse";
        boolean priv = !r.path("public").asBoolean(true);
        String defaultBranch = r.path("defaultBranch").path("displayId").asText("main");
        return new QuickImportRepoDto(repoName, fullName, webUrl, defaultBranch, priv, "");
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
            String cloneUrl = buildCloneUrl(parsed);
            GitCloneCredentials credentials = resolveCloneCredentials(parsed, token, conn.getVcsUsername());
            gitCloneExecutor.clone(cloneUrl, credentials, branch, cloneDir, jobId);

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

            projectCliKeyPolicyService.assertScanIngestAllowed(project.getId());
            ApiKeyResult keyResult = getOrIssueApiKey(project);

            // 6. Submit scan ───────────────────────────────
            updateJob(jobId, Phase.SCANNING, "Submitting scan payload (" + deps.components.size() + " components)…",
                    project.getId(), project.getName(), null, false, deps.ecosystem, deps.components.size());

            String scanVersion = branch != null && !branch.isBlank() ? branch : "default";
            ScanPayload payload = buildScanPayload(deps, scanVersion);
            ScanResult scanResult;
            try {
                scanResult = scanIngestService.ingest(project.getId(), payload, userId);
            } catch (Exception ingestEx) {
                // The project will appear on the Projects page with a "No scan data" indicator.
                // The user can re-import to retry.
                log.error("[QuickImport][{}] Scan ingest failed for project {}: {}",
                        jobId, project.getId(), ingestEx.getMessage(), ingestEx);
                updateJob(jobId, Phase.FAILED,
                        messageSource.getMessage("quickImport.error.scanSubmitFailed",
                                null, LocaleContextHolder.getLocale()),
                        project.getId(), project.getName(),
                        keyResult.token, keyResult.isNew,
                        deps.ecosystem, deps.components.size());
                return;
            }

            String actorEmail = userRepository.findById(userId)
                    .map(u -> u.getEmail())
                    .orElse("user:" + userId);
            auditLogService.logAnonymous(actorEmail, "SCAN.INGEST", "PROJECT",
                    project.getId().toString(), scanVersion,
                    "source=quick-import scanId=" + scanResult.getId());

            // 7. Wait for async enrichment (vulnerability analysis + AI) ──
            updateJob(jobId, Phase.ENRICHING,
                    "Analyzing components…",
                    project.getId(), project.getName(),
                    keyResult.token, keyResult.isNew,
                    deps.ecosystem, deps.components.size(), scanResult.getId());

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

        // On-premise first: when URL host matches a stored connection's serverUrl
        ParsedRepoUrl fromConnection = parseFromStoredConnection(host, path, userConnections);
        if (fromConnection != null) return fromConnection;

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

        // ── Self-hosted fallback ─────────────────────────────────────────
        ParsedRepoUrl fallback = parseFromStoredConnection(host, path, userConnections);
        if (fallback != null) return fallback;

        log.warn("[QuickImport] Unknown host '{}' — cannot determine VCS provider.", host);
        return null;
    }

    private ParsedRepoUrl parseFromStoredConnection(String host, String path, List<UserVcsConnection> userConnections) {
        for (UserVcsConnection conn : userConnections) {
            if (conn.getServerUrl() == null || conn.getServerUrl().isBlank()) continue;
            try {
                String connHost = conn.getServerUrl().replaceAll("https?://", "").split("/")[0].toLowerCase();
                if (!connHost.equals(host)) continue;

                if (conn.getProvider() == VcsProvider.BITBUCKET) {
                    Matcher scm = Pattern.compile("^/scm/([^/]+)/([^/]+)").matcher(path);
                    if (scm.find()) {
                        String proj = scm.group(1);
                        String repo = scm.group(2);
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, proj, repo, "/scm/" + proj + "/" + repo);
                    }
                    Matcher projects = Pattern.compile("^/projects/([^/]+)/repos/([^/]+)").matcher(path);
                    if (projects.find()) {
                        String proj = projects.group(1).toLowerCase();
                        String repo = projects.group(2);
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, proj, repo, "/scm/" + proj + "/" + repo);
                    }
                    String[] parts = splitTwoPathSegments(path);
                    if (parts != null) {
                        return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, parts[0], parts[1], null);
                    }
                } else {
                    String[] parts = splitTwoPathSegments(path);
                    if (parts != null) {
                        return new ParsedRepoUrl(conn.getProvider(), host, parts[0], parts[1], null);
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Extracts the first two non-empty path segments from a path like {@code /owner/repo/...}. */
    private static String[] splitTwoPathSegments(String path) {
        String[] segs = path.replaceAll("^/+", "").split("/", -1);
        if (segs.length < 2 || segs[0].isBlank() || segs[1].isBlank()) return null;
        return new String[]{ segs[0], segs[1] };
    }

    private String buildCloneUrl(ParsedRepoUrl parsed) {
        String repoPath = parsed.clonePath() != null
                ? parsed.clonePath()
                : "/" + parsed.owner() + "/" + parsed.repo();
        return switch (parsed.provider()) {
            case GITHUB, GITLAB ->
                    "https://" + parsed.host() + "/" + parsed.owner() + "/" + parsed.repo() + ".git";
            case BITBUCKET -> "https://" + parsed.host() + repoPath + ".git";
        };
    }

    private GitCloneCredentials resolveCloneCredentials(ParsedRepoUrl parsed, String token, String vcsUsername) {
        return switch (parsed.provider()) {
            case GITHUB, GITLAB -> new GitCloneCredentials("oauth2", token);
            case BITBUCKET -> bitbucketCloudClient.cloneCredentials(vcsUsername, token);
        };
    }

    // ── Dependency parsing ─────────────────────────────────────────────────

    private record ParsedDependencies(String ecosystem, List<ScanPayload.ComponentPayload> components) {}

    private record GradleComponent(String name, String version, List<List<ScanPayload.DependencyNodeRef>> paths) {}

    private ParsedDependencies parseDependencies(Path cloneDir, String repoName) {
        List<ScanPayload.ComponentPayload> allComps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> ecosystems = new ArrayList<>();

        // ── Maven: walk all pom.xml files ──────────────────────────────────────
        List<Path> pomFiles = walkManifests(cloneDir, Set.of("pom.xml"), MANIFEST_SKIP_DIRS);
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
            log.info("[QuickImport][Multi] Maven: {} pom.xml → {} components so far", pomFiles.size(), allComps.size());
        }

        // ── Gradle: build.gradle / build.gradle.kts ────────────────────────────
        List<Path> gradleFiles = walkManifests(cloneDir,
                Set.of("build.gradle", "build.gradle.kts"), MANIFEST_SKIP_DIRS);
        if (!gradleFiles.isEmpty()) {
            if (!ecosystems.contains("MAVEN")) ecosystems.add("MAVEN");
            List<ScanPayload.ComponentPayload> gradleDeps = runGradleDependencies(cloneDir, repoName);
            if (gradleDeps == null || gradleDeps.isEmpty()) {
                gradleDeps = parseGradleStatic(cloneDir, repoName).components();
            } else {
                gradleDeps = bomVersionResolver.enrichComponentVersions(cloneDir, gradleDeps);
            }
            mergeComponents(allComps, seen, gradleDeps, "MAVEN");
            // Version catalogs cover libs.xxx references not captured by static regex
            mergeComponents(allComps, seen, parseVersionCatalogs(cloneDir, repoName), "MAVEN");
            log.info("[QuickImport][Multi] Gradle: {} build files → {} components so far", gradleFiles.size(), allComps.size());
        }

        // ── npm: lock files first (full transitive), then package.json ──────────
        for (Path lock : walkManifests(cloneDir,
                Set.of("package-lock.json", "yarn.lock", "pnpm-lock.yaml"), MANIFEST_SKIP_DIRS)) {
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
        }
        if (!ecosystems.contains("NPM")) {
            for (Path pkg : walkManifests(cloneDir, Set.of("package.json"), MANIFEST_SKIP_DIRS)) {
                List<ScanPayload.ComponentPayload> npmComps = parseNpmPackageJson(pkg.getParent(), repoName).components();
                if (!npmComps.isEmpty()) {
                    ecosystems.add("NPM");
                    mergeComponents(allComps, seen, npmComps, "NPM");
                }
            }
        }

        // ── Python: lock files first, then requirements.txt ────────────────────
        for (Path lock : walkManifests(cloneDir,
                Set.of("poetry.lock", "uv.lock", "Pipfile.lock"), MANIFEST_SKIP_DIRS)) {
            String fn = lock.getFileName().toString();
            List<ScanPayload.ComponentPayload> pyComps = fn.equals("Pipfile.lock")
                    ? parsePipfileLock(lock.getParent(), repoName)
                    : parseTomlPackageLock(lock, "PYPI", repoName);
            if (pyComps != null && !pyComps.isEmpty()) {
                if (!ecosystems.contains("PYPI")) ecosystems.add("PYPI");
                mergeComponents(allComps, seen, pyComps, "PYPI");
            }
        }
        if (!ecosystems.contains("PYPI")) {
            for (Path req : walkManifests(cloneDir, Set.of("requirements.txt"), MANIFEST_SKIP_DIRS)) {
                ParsedDependencies pd = parsePython(req.getParent(), repoName);
                if (!pd.components().isEmpty()) {
                    ecosystems.add("PYPI");
                    mergeComponents(allComps, seen, pd.components(), "PYPI");
                    break;
                }
            }
        }

        // ── Cargo: Cargo.lock, then Cargo.toml fallback ────────────────────────
        for (Path lock : walkManifests(cloneDir, Set.of("Cargo.lock"), MANIFEST_SKIP_DIRS)) {
            List<ScanPayload.ComponentPayload> cargoComps = parseTomlPackageLock(lock, "CARGO", repoName);
            if (cargoComps != null && !cargoComps.isEmpty()) {
                if (!ecosystems.contains("CARGO")) ecosystems.add("CARGO");
                mergeComponents(allComps, seen, cargoComps, "CARGO");
            }
        }
        if (!ecosystems.contains("CARGO")) {
            for (Path toml : walkManifests(cloneDir, Set.of("Cargo.toml"), MANIFEST_SKIP_DIRS)) {
                List<ScanPayload.ComponentPayload> cargoComps = parseCargoToml(toml.getParent(), repoName);
                if (cargoComps != null && !cargoComps.isEmpty()) {
                    ecosystems.add("CARGO");
                    mergeComponents(allComps, seen, cargoComps, "CARGO");
                }
            }
        }

        // ── Go: go.sum, then go.mod fallback ───────────────────────────────────
        for (Path sum : walkManifests(cloneDir, Set.of("go.sum"), MANIFEST_SKIP_DIRS)) {
            List<ScanPayload.ComponentPayload> goComps = parseGoSum(sum.getParent(), repoName);
            if (goComps != null && !goComps.isEmpty()) {
                if (!ecosystems.contains("GO")) ecosystems.add("GO");
                mergeComponents(allComps, seen, goComps, "GO");
            }
        }
        if (!ecosystems.contains("GO")) {
            for (Path gomod : walkManifests(cloneDir, Set.of("go.mod"), MANIFEST_SKIP_DIRS)) {
                List<ScanPayload.ComponentPayload> goComps = parseGoModDeclared(gomod.getParent(), repoName);
                if (goComps != null && !goComps.isEmpty()) {
                    ecosystems.add("GO");
                    mergeComponents(allComps, seen, goComps, "GO");
                }
            }
        }

        // ── NuGet: packages.lock.json / .csproj ────────────────────────────────
        for (Path lock : walkManifests(cloneDir, Set.of("packages.lock.json"), MANIFEST_SKIP_DIRS)) {
            List<ScanPayload.ComponentPayload> nugetComps = parseNuGetLockFile(lock.getParent(), repoName);
            if (nugetComps != null && !nugetComps.isEmpty()) {
                if (!ecosystems.contains("NUGET")) ecosystems.add("NUGET");
                mergeComponents(allComps, seen, nugetComps, "NUGET");
            }
        }
        if (!ecosystems.contains("NUGET") && hasCsprojFiles(cloneDir)) {
            ParsedDependencies nd = parseNuGetStatic(cloneDir, repoName);
            if (!nd.components().isEmpty()) {
                ecosystems.add("NUGET");
                mergeComponents(allComps, seen, nd.components(), "NUGET");
            }
        }

        // ── Ruby: Gemfile.lock ────────────────────────────────────────────────
        for (Path lock : walkManifests(cloneDir, Set.of("Gemfile.lock"), MANIFEST_SKIP_DIRS)) {
            List<ScanPayload.ComponentPayload> rubyComps = parseGemfileLock(lock.getParent(), repoName);
            if (rubyComps != null && !rubyComps.isEmpty()) {
                if (!ecosystems.contains("RUBYGEMS")) ecosystems.add("RUBYGEMS");
                mergeComponents(allComps, seen, rubyComps, "RUBYGEMS");
            }
        }

        if (allComps.isEmpty()) {
            log.warn("[QuickImport] No recognized manifests in '{}' — empty component list.", repoName);
            return new ParsedDependencies("UNKNOWN", List.of());
        }
        String primary = ecosystems.get(0);
        log.info("[QuickImport] Multi-scan '{}': {} components across ecosystems={}", repoName, allComps.size(), ecosystems);
        return new ParsedDependencies(primary, allComps);
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

    /** Parses a single {@code pom.xml} file and returns its non-test, non-system direct dependencies. */
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
                if ("test".equalsIgnoreCase(scope) || "system".equalsIgnoreCase(scope)
                        || "provided".equalsIgnoreCase(scope)) continue;
                comps.add(buildComponent(groupId + ":" + artifactId, version, "MAVEN"));
            }
            log.debug("[QuickImport][Maven] Parsed {} deps from {}", comps.size(), pomFile);
            comps = bomVersionResolver.enrichComponentVersions(projectDir, comps);
        } catch (Exception e) {
            log.warn("[QuickImport][Maven] Failed to parse {}: {}", pomFile, e.getMessage());
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
            log.debug("[QuickImport][Maven] No mvnw in '{}', using static pom.xml parse", repoName);
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
            log.info("[QuickImport][Maven] Running mvnw dependency:list for '{}'", repoName);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
            if (!finished) { proc.destroyForcibly(); log.warn("[QuickImport][Maven] mvnw timed out for '{}'", repoName); return null; }
            if (proc.exitValue() != 0) {
                log.warn("[QuickImport][Maven] mvnw exited {} for '{}', falling back to static parse", proc.exitValue(), repoName);
                return null;
            }
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
            log.info("[QuickImport][Maven] mvnw \u2192 {} components for '{}'", comps.size(), repoName);
            return comps.isEmpty() ? null : comps;
        } catch (Exception e) {
            log.warn("[QuickImport][Maven] mvnw failed for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    /**
     * Recursively finds all files whose name is in {@code fileNames} under {@code root},
     * skipping any path segment listed in {@code skipDirs}.
     */
    private List<Path> walkManifests(Path root, Set<String> fileNames, Set<String> skipDirs) {
        List<Path> result = new ArrayList<>();
        final Path rootReal;
        try {
            rootReal = new CloneRootPathGuard(root).root();
        } catch (IOException e) {
            log.warn("[QuickImport] walkManifests: invalid clone root '{}': {}", root, e.getMessage());
            return result;
        }
        try {
            Files.walkFileTree(rootReal, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(rootReal)) {
                        Path rel = rootReal.relativize(dir);
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (skipDirs.contains(rel.getName(i).toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!fileReal.startsWith(rootReal)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!fileNames.contains(fileReal.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = rootReal.relativize(fileReal);
                        for (int i = 0; i < rel.getNameCount() - 1; i++) {
                            if (skipDirs.contains(rel.getName(i).toString())) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        result.add(fileReal);
                    } catch (IOException e) {
                        log.debug("[QuickImport] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[QuickImport] walkManifests error under '{}': {}", root, e.getMessage());
        }
        return result;
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
    private List<ScanPayload.ComponentPayload> parseVersionCatalogs(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        List<Path> catalogs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            walk.filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".versions.toml"))
                .forEach(catalogs::add);
        } catch (IOException e) {
            log.warn("[QuickImport][Gradle] Error finding version catalogs: {}", e.getMessage());
            return comps;
        }
        if (catalogs.isEmpty()) { log.debug("[QuickImport][Gradle] No *.versions.toml in '{}'", repoName); return comps; }
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
                log.info("[QuickImport][Gradle] Version catalog {} \u2192 {} entries", catalog.getFileName(), comps.size());
            } catch (Exception e) {
                log.warn("[QuickImport][Gradle] Failed to parse {}: {}", catalog, e.getMessage());
            }
        }
        return comps;
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
                log.info("[QuickImport][Gradle] Running gradlew dependencies --configuration {} for '{}'", config, repoName);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
                if (!finished) { proc.destroyForcibly(); log.warn("[QuickImport][Gradle] gradlew timed out for '{}'", repoName); return null; }
                if (proc.exitValue() != 0) {
                    log.debug("[QuickImport][Gradle] gradlew --configuration {} exited {} for '{}', trying next", config, proc.exitValue(), repoName);
                    continue;
                }
                List<ScanPayload.ComponentPayload> comps = parseGradleTreeOutput(output, repoName);
                if (!comps.isEmpty()) {
                    log.info("[QuickImport][Gradle] gradlew --configuration {} resolved {} components for '{}'", config, comps.size(), repoName);
                    return comps;
                }
            }
            log.warn("[QuickImport][Gradle] gradlew produced no components for '{}', falling back to static parse", repoName);
            return null;
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
            path.add(ScanPayload.DependencyNodeRef.create(projectName, "local"));
            for (int lvl = 0; lvl < depth; lvl++) {
                String[] anc = depStack.get(lvl);
                if (anc != null) path.add(ScanPayload.DependencyNodeRef.create(anc[0], anc[1]));
            }
            path.add(ScanPayload.DependencyNodeRef.create(compName, compVer));

            String key = compName + ":" + (compVer != null ? compVer : "");
            depComps.computeIfAbsent(key, k -> new GradleComponent(compName, compVer, new ArrayList<>()))
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

    /** Static fallback: parse build.gradle declarations and resolve versions from BOM POMs. */
    private ParsedDependencies parseGradleStatic(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps;
        try {
            comps = bomVersionResolver.parseGradleDeclaredWithBom(dir);
            log.info("[QuickImport][Gradle] Static+BOM parsed {} components in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Gradle] Failed to static-parse build.gradle: {}", e.getMessage());
            comps = List.of();
        }
        return new ParsedDependencies("MAVEN", comps);
    }

    // Python: requirements.txt fallback (lock files are handled in parseDependencies)
    private ParsedDependencies parsePython(Path dir, String repoName) {
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
            log.info("[QuickImport][Cargo] Parsed {} components from Cargo.toml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[QuickImport][Cargo] Failed to parse Cargo.toml: {}", e.getMessage());
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
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            return walk.anyMatch(p -> p.getFileName().toString().endsWith(".csproj"));
        } catch (IOException e) {
            return false;
        }
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
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }

    private ScanPayload buildScanPayload(ParsedDependencies deps, String repoName) {
        return ScanPayload.create(repoName, deps.components);
    }

    private record ApiKeyResult(String token, boolean isNew) {}

    /**
     * Returns an existing active API key for the project, or issues a new one when the project
     * has no api_keys rows. Revoked/inactive-only projects must not reach this method
     * ({@link ProjectCliKeyPolicyService#assertScanIngestAllowed} runs first).
     */
    private ApiKeyResult getOrIssueApiKey(Project project) {
        return switch (projectCliKeyPolicyService.resolve(project.getId())) {
            case ACTIVE_PRESENT -> new ApiKeyResult(null, false);
            case NONE -> {
                IssuedApiKey issued = apiKeyService.issue(project.getId(), "quick-import", null);
                yield new ApiKeyResult(issued.plainToken(), true);
            }
            case INACTIVE_ONLY -> throw new IllegalStateException("CLI key policy blocked ingest");
        };
    }

    private String decryptToken(UserVcsConnection conn) {
        try {
            return encryptionService.decrypt(conn.getAccessTokenEncrypted());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "VCS token could not be decrypted. Reconnect the integration in Settings.", e);
        }
    }

    private Path getCloneBaseReal() throws IOException {
        Path base = cloneBaseReal;
        if (base == null) {
            synchronized (this) {
                base = cloneBaseReal;
                if (base == null) {
                    cloneBaseReal = CloneRootPathGuard.resolveConfiguredBase(configuredTempDir);
                    base = cloneBaseReal;
                }
            }
        }
        return base;
    }

    private static String safePathSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unknown";
        }
        return segment.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private Path createTempCloneDir(String owner, String repo, String jobId) throws IOException {
        Path base = getCloneBaseReal();
        Path dir = base.resolve(safePathSegment(owner) + "_" + safePathSegment(repo) + "_" + jobId);
        Files.createDirectories(dir);
        Path dirReal = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!dirReal.startsWith(base)) {
            throw new SecurityException("Clone directory escapes configured root: " + dir);
        }
        return dirReal;
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
        updateJob(jobId, phase, message, projectId, projectName, apiToken, newApiKey, ecosystem, componentCount, null);
    }

    private void updateJob(String jobId, Phase phase, String message,
                           Long projectId, String projectName,
                           String apiToken, Boolean newApiKey,
                           String ecosystem, Integer componentCount,
                           Long scanResultId) {
        QuickImportJobStatus prev = jobs.getOrDefault(jobId,
                baseJobBuilder(jobId).phase(Phase.QUEUED).build());
        patchJob(jobId, b -> {
            b.phase(phase).message(message)
                    .projectId(projectId).projectName(projectName)
                    .apiToken(apiToken).newApiKey(newApiKey)
                    .ecosystem(ecosystem).componentCount(componentCount)
                    .scanResultId(scanResultId)
                    .queuePosition(phase == Phase.QUEUED ? prev.getQueuePosition() : null)
                    .percent(phasePercent(phase, scanResultId));
            if (prev.getRepoLabel() != null) b.repoLabel(prev.getRepoLabel());
        });
    }

    private void patchJob(String jobId, Consumer<QuickImportJobStatus.QuickImportJobStatusBuilder> patch) {
        QuickImportJobStatus prev = jobs.getOrDefault(jobId,
                baseJobBuilder(jobId).phase(Phase.QUEUED).build());
        QuickImportJobStatus.QuickImportJobStatusBuilder builder = prev.toBuilder();
        patch.accept(builder);
        builder.activeSlotsUsed(runningImports.get()).maxConcurrentSlots(maxConcurrentImports);
        QuickImportJobStatus next = builder.build();
        jobs.put(jobId, next);
        notifyJobUpdate(jobId);
    }

    private QuickImportJobStatus.QuickImportJobStatusBuilder baseJobBuilder(String jobId) {
        return QuickImportJobStatus.builder()
                .jobId(jobId)
                .activeSlotsUsed(runningImports.get())
                .maxConcurrentSlots(maxConcurrentImports);
    }

    private void refreshQueuePositions() {
        int position = runningImports.get() + 1;
        for (PendingImport pending : pendingQueue) {
            QuickImportJobStatus job = jobs.get(pending.jobId());
            if (job != null && job.getPhase() == Phase.QUEUED) {
                jobs.put(pending.jobId(), job.toBuilder()
                        .queuePosition(position)
                        .message(queuedMessage(position))
                        .percent(0)
                        .activeSlotsUsed(runningImports.get())
                        .build());
                notifyJobUpdate(pending.jobId());
            }
            position++;
        }
    }

    private String queuedMessage(int position) {
        if (position <= maxConcurrentImports) {
            return messageSource.getMessage("quickImport.queue.next",
                    new Object[]{position}, "Starting soon…", LocaleContextHolder.getLocale());
        }
        int wait = position - maxConcurrentImports;
        return messageSource.getMessage("quickImport.queue.waiting",
                new Object[]{wait}, "Waiting in queue (#" + wait + ")…", LocaleContextHolder.getLocale());
    }

    private static int phasePercent(Phase phase, Long scanResultId) {
        return switch (phase) {
            case QUEUED -> 0;
            case CLONING -> 12;
            case PARSING -> 35;
            case SCANNING -> 55;
            case ENRICHING -> 60;
            case DONE -> 100;
            case FAILED -> 0;
        };
    }

    private static String extractRepoLabel(String repoUrl) {
        try {
            String path = URI.create(repoUrl.strip()).getPath();
            if (path != null && path.length() > 1) {
                return path.startsWith("/") ? path.substring(1) : path;
            }
        } catch (Exception ignored) { }
        return repoUrl.length() > 48 ? repoUrl.substring(0, 45) + "…" : repoUrl;
    }

    private void notifyJobUpdate(String jobId) {
        CopyOnWriteArrayList<SseEmitter> emitters = jobEmitters.get(jobId);
        if (emitters == null || emitters.isEmpty()) return;
        Long owner = jobOwners.get(jobId);
        // Live SSE updates carry the in-memory status (full apiToken until job eviction).
        QuickImportJobStatus status = owner != null
                ? resolveJobStatus(jobId)
                : jobs.get(jobId);
        if (status == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("job-update").data(status, MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                removeEmitter(jobId, emitter);
            }
        }
    }

    private void removeEmitter(String jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = jobEmitters.get(jobId);
        if (list != null) list.remove(emitter);
    }

    /** Removes jobs older than 30 minutes from memory. Runs every 5 minutes. */
    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void evictExpiredJobs() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(30));
        jobCreatedAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isBefore(cutoff)) {
                purgeJobFromMemory(entry.getKey());
                return true;
            }
            return false;
        });
    }

    /** Drops all in-memory references for a job (status, SSE, per-user index). */
    private void purgeJobFromMemory(String jobId) {
        disposeJobEmitters(jobId);
        Long ownerId = jobOwners.get(jobId);
        if (ownerId != null) {
            CopyOnWriteArrayList<String> ids = userJobIds.get(ownerId);
            if (ids != null) {
                ids.remove(jobId);
                if (ids.isEmpty()) {
                    userJobIds.remove(ownerId, ids);
                }
            }
        }
        jobs.remove(jobId);
        jobOwners.remove(jobId);
        apiTokenRevealed.remove(jobId);
        log.debug("[QuickImport] Evicted expired job {}", jobId);
    }

    private void disposeJobEmitters(String jobId) {
        CopyOnWriteArrayList<SseEmitter> emitters = jobEmitters.remove(jobId);
        if (emitters == null) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already closed or timed out
            }
        }
    }

}
