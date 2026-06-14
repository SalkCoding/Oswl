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
import com.salkcoding.oswl.dto.QuickImportJobsResponse;
import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import com.salkcoding.oswl.exception.QuickImportQueueFullException;
import com.salkcoding.oswl.dto.QuickImportRepoDto;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Locale;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "OsWL-App/1.0";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${oswl.clone.temp-dir:}")
    private String configuredTempDir;

    @Value("${oswl.quick-import.max-concurrent:3}")
    private int maxConcurrentImports;

    @Value("${oswl.quick-import.max-queued-per-user:3}")
    private int maxQueuedPerUser;

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
    private final DependencyManifestParserService dependencyManifestParserService;

    /** In-memory job tracker. Entries are removed after 30 minutes by {@link #evictExpiredJobs()}. */
    private final ConcurrentHashMap<String, QuickImportJobStatus> jobs = new ConcurrentHashMap<>();
    /** Tracks job creation times for TTL-based eviction. */
    private final ConcurrentHashMap<String, Instant> jobCreatedAt = new ConcurrentHashMap<>();
    /** Job owner ??used to prevent cross-user status polling (IDOR). */
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

    private record PendingImport(String jobId, String repoUrl, String branch, Long userId, String repoLabel,
                                 Locale locale) {}

    // ?? Public API ????????????????????????????????????????????????????????

    /**
     * Starts an asynchronous import job and immediately returns the job ID.
     *
     * @param repoUrl   the repository URL entered by the user
     * @param branch    requested branch (null = default branch)
     * @param userId    authenticated user ID
     * @return job ID (UUID string)
     */
    public String startImport(String repoUrl, String branch, Long userId) {
        int userQueued = countUserQueuedJobs(userId);
        if (userQueued >= maxQueuedPerUser) {
            throw new QuickImportQueueFullException(maxQueuedPerUser);
        }

        String jobId = UUID.randomUUID().toString();
        String repoLabel = extractRepoLabel(repoUrl);
        int queuePosition = pendingQueue.size() + runningImports.get() + 1;

        jobs.put(jobId, baseJobBuilder(jobId)
                .phase(Phase.QUEUED)
                .message(null)
                .messageKey(null)
                .messageArgs(null)
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

        Locale locale = LocaleContextHolder.getLocale();
        pendingQueue.offer(new PendingImport(jobId, repoUrl, branch, userId, repoLabel, locale));
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

    public QuickImportJobsResponse listJobsSnapshot(Long userId) {
        return QuickImportJobsResponse.builder()
                .jobs(listJobsForUser(userId))
                .activeSlotsUsed(runningImports.get())
                .maxConcurrentSlots(maxConcurrentImports)
                .userQueuedCount(countUserQueuedJobs(userId))
                .userRunningCount(countUserRunningJobs(userId))
                .maxQueuedSlots(maxQueuedPerUser)
                .build();
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
                    .message(null)
                    .messageKey(null)
                    .messageArgs(null)
                    .queuePosition(null)
                    .percent(phasePercent(Phase.CLONING, null)));
            runImport(jobId, task.repoUrl(), task.branch(), task.userId());
        } catch (com.salkcoding.oswl.exception.ConflictException e) {
            log.warn("[QuickImport][{}] Blocked by CLI key policy: {}", jobId, e.getMessage());
            failJob(jobId, QuickImportMessageKeys.CLI_POLICY_BLOCKED, List.of(),
                    null, null, null, null, null, null);
        } catch (Throwable t) {
            log.error("[QuickImport][{}] Unhandled error", jobId, t);
            failJob(jobId, classifyFailureKey(t), List.of(),
                    null, null, null, null, null, null);
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
                            int count = current.getComponentCount() != null ? current.getComponentCount() : 0;
                            QuickImportJobStatus done = current.toBuilder()
                                    .phase(Phase.DONE)
                                    .message(null)
                                    .messageKey(QuickImportMessageKeys.IMPORT_COMPLETE)
                                    .messageArgs(List.of(String.valueOf(count)))
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
                                    .message(null)
                                    .messageKey(QuickImportMessageKeys.ENRICHMENT_FAILED)
                                    .messageArgs(List.of())
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
        return token.substring(0, 8) + "\u2026" + token.substring(token.length() - 4);
    }

    // ?? Repo browser (for Quick Import list UI) ???????????????????????????

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
            throw new com.salkcoding.oswl.exception.QuickImportUpstreamException(
                    QuickImportMessageKeys.TOKEN_DECRYPT);
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
                    QuickImportMessageKeys.LOAD_REPOS);
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
        // Fine-grained project-scoped tokens lack that context ??fall back to min_access_level.
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

        // Primary: /rest/api/1.0/projects ??repos per project (Data Center standard)
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
        // 1. Parse the URL (pass user connections so self-hosted hosts can be identified) ????
        List<UserVcsConnection> userConns = vcsConnectionRepository.findByUserIdAndActiveTrue(userId);
        ParsedRepoUrl parsed = parseRepoUrl(repoUrl, userConns);
        if (parsed == null) {
            failJob(jobId, QuickImportMessageKeys.INVALID_REPO_URL, List.of(),
                    null, null, null, null, null, null);
            return;
        }

        // 2. Resolve clone credentials (optional ??public repos clone anonymously) ??
        Optional<UserVcsConnection> connOpt =
                vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(userId, parsed.provider);
        GitCloneCredentials credentials = null;
        if (connOpt.isPresent()) {
            UserVcsConnection conn = connOpt.get();
            String token = decryptToken(conn);
            credentials = resolveCloneCredentials(parsed, token, conn.getVcsUsername());
        }

        // 3. Clone ?????????????????????????????????????????????????????????
        advanceJob(jobId, Phase.CLONING, null, null, null, null, null, null, null);

        Path cloneDir = createTempCloneDir(parsed.owner, parsed.repo, jobId);
        try {
            String cloneUrl = buildCloneUrl(parsed);
            gitCloneExecutor.clone(cloneUrl, credentials, branch, cloneDir, jobId);

            // 4. Parse dependencies ????????????????????????????????????????
            advanceJob(jobId, Phase.PARSING, null, null, null, null, null, null, null);

            DependencyManifestParserService.ParseResult deps =
                    dependencyManifestParserService.parseDependencies(cloneDir, parsed.owner + "/" + parsed.repo);

            // 5. Create/find project and API key ??????????????????????????
            Project project = projectService.upsertFromGitHub(
                    parsed.provider,
                    parsed.owner, parsed.repo,
                    branch != null && !branch.isBlank() ? branch : "default",
                    userId);

            projectCliKeyPolicyService.assertScanIngestAllowed(project.getId());
            ApiKeyResult keyResult = getOrIssueApiKey(project);

            // 6. Submit scan ???????????????????????????????
            advanceJob(jobId, Phase.SCANNING, project.getId(), project.getName(), null, false,
                    deps.ecosystem(), deps.components().size());

            String scanVersion = branch != null && !branch.isBlank() ? branch : "default";
            ScanPayload payload = dependencyManifestParserService.buildScanPayload(deps, scanVersion);
            ScanResult scanResult;
            try {
                scanResult = scanIngestService.ingest(project.getId(), payload);
            } catch (Exception ingestEx) {
                // The project will appear on the Projects page with a "No scan data" indicator.
                // The user can re-import to retry.
                log.error("[QuickImport][{}] Scan ingest failed for project {}: {}",
                        jobId, project.getId(), ingestEx.getMessage(), ingestEx);
                failJob(jobId, QuickImportMessageKeys.SCAN_SUBMIT_FAILED, List.of(),
                        project.getId(), project.getName(),
                        keyResult.token, keyResult.isNew,
                        deps.ecosystem(), deps.components().size());
                return;
            }

            String actorEmail = userRepository.findById(userId)
                    .map(u -> u.getEmail())
                    .orElse("user:" + userId);
            auditLogService.logAnonymous(actorEmail, "SCAN.INGEST", "PROJECT",
                    project.getId().toString(), scanVersion,
                    "source=quick-import scanId=" + scanResult.getId());

            // 7. Wait for async enrichment (vulnerability analysis + AI) ??
            advanceJob(jobId, Phase.ENRICHING, project.getId(), project.getName(),
                    keyResult.token, keyResult.isNew,
                    deps.ecosystem(), deps.components().size(), scanResult.getId());

        } finally {
            deleteDirectory(cloneDir);
        }
    }

    // ?? URL parsing ????????????????????????????????????????????????????????

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

        // ?? Bitbucket Cloud ??????????????????????????????????????????????
        if (host.equals("bitbucket.org") || host.endsWith(".bitbucket.org")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.BITBUCKET, host, parts[0], parts[1], null);
        }

        // ?? GitHub (cloud + enterprise) ??????????????????????????????????
        if (host.equals("github.com") || host.endsWith(".github.com")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.GITHUB, host, parts[0], parts[1], null);
        }

        // ?? GitLab (cloud + self-hosted) ?????????????????????????????????
        if (host.equals("gitlab.com") || host.contains("gitlab")) {
            String[] parts = splitTwoPathSegments(path);
            if (parts == null) return null;
            return new ParsedRepoUrl(VcsProvider.GITLAB, host, parts[0], parts[1], null);
        }

        // ?? Self-hosted fallback ?????????????????????????????????????????
        ParsedRepoUrl fallback = parseFromStoredConnection(host, path, userConnections);
        if (fallback != null) return fallback;

        log.warn("[QuickImport] Unknown host '{}' ??cannot determine VCS provider.", host);
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

    private void advanceJob(String jobId, Phase phase,
                          Long projectId, String projectName,
                          String apiToken, Boolean newApiKey,
                          String ecosystem, Integer componentCount) {
        advanceJob(jobId, phase, projectId, projectName, apiToken, newApiKey, ecosystem, componentCount, null);
    }

    private void advanceJob(String jobId, Phase phase,
                          Long projectId, String projectName,
                          String apiToken, Boolean newApiKey,
                          String ecosystem, Integer componentCount,
                          Long scanResultId) {
        QuickImportJobStatus prev = jobs.getOrDefault(jobId,
                baseJobBuilder(jobId).phase(Phase.QUEUED).build());
        patchJob(jobId, b -> {
            b.phase(phase)
                    .message(null)
                    .messageKey(null)
                    .messageArgs(null)
                    .projectId(projectId).projectName(projectName)
                    .apiToken(apiToken).newApiKey(newApiKey)
                    .ecosystem(ecosystem).componentCount(componentCount)
                    .scanResultId(scanResultId)
                    .queuePosition(phase == Phase.QUEUED ? prev.getQueuePosition() : null)
                    .percent(phasePercent(phase, scanResultId));
            if (prev.getRepoLabel() != null) b.repoLabel(prev.getRepoLabel());
        });
    }

    private void failJob(String jobId, String messageKey, List<String> messageArgs,
                         Long projectId, String projectName,
                         String apiToken, Boolean newApiKey,
                         String ecosystem, Integer componentCount) {
        failJob(jobId, messageKey, messageArgs, projectId, projectName,
                apiToken, newApiKey, ecosystem, componentCount, null);
    }

    private void failJob(String jobId, String messageKey, List<String> messageArgs,
                         Long projectId, String projectName,
                         String apiToken, Boolean newApiKey,
                         String ecosystem, Integer componentCount,
                         Long scanResultId) {
        QuickImportJobStatus prev = jobs.getOrDefault(jobId,
                baseJobBuilder(jobId).phase(Phase.QUEUED).build());
        patchJob(jobId, b -> {
            b.phase(Phase.FAILED)
                    .message(null)
                    .messageKey(messageKey)
                    .messageArgs(messageArgs != null ? messageArgs : List.of())
                    .projectId(projectId).projectName(projectName)
                    .apiToken(apiToken).newApiKey(newApiKey)
                    .ecosystem(ecosystem).componentCount(componentCount)
                    .scanResultId(scanResultId)
                    .queuePosition(null)
                    .percent(0);
            if (prev.getRepoLabel() != null) b.repoLabel(prev.getRepoLabel());
        });
    }

    private static String classifyFailureKey(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return QuickImportMessageKeys.UNEXPECTED;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("filename too long")) {
            return QuickImportMessageKeys.CLONE_PATH_TOO_LONG;
        }
        if (lower.contains("timed out after")) {
            return QuickImportMessageKeys.CLONE_TIMEOUT;
        }
        if (lower.contains("git clone failed")) {
            return QuickImportMessageKeys.CLONE_FAILED;
        }
        return QuickImportMessageKeys.UNEXPECTED;
    }

    private void patchJob(String jobId, Consumer<QuickImportJobStatus.QuickImportJobStatusBuilder> patch) {
        QuickImportJobStatus prev = jobs.getOrDefault(jobId,
                baseJobBuilder(jobId).phase(Phase.QUEUED).build());
        QuickImportJobStatus.QuickImportJobStatusBuilder builder = prev.toBuilder();
        patch.accept(builder);
        builder.activeSlotsUsed(runningImports.get())
                .maxConcurrentSlots(maxConcurrentImports)
                .maxQueuedSlots(maxQueuedPerUser);
        QuickImportJobStatus next = builder.build();
        jobs.put(jobId, next);
        notifyJobUpdate(jobId);
    }

    private QuickImportJobStatus.QuickImportJobStatusBuilder baseJobBuilder(String jobId) {
        return QuickImportJobStatus.builder()
                .jobId(jobId)
                .activeSlotsUsed(runningImports.get())
                .maxConcurrentSlots(maxConcurrentImports)
                .maxQueuedSlots(maxQueuedPerUser);
    }

    private int countUserQueuedJobs(Long userId) {
        if (userId == null) return 0;
        int count = 0;
        for (var entry : jobOwners.entrySet()) {
            if (!userId.equals(entry.getValue())) continue;
            QuickImportJobStatus job = jobs.get(entry.getKey());
            if (job != null && job.getPhase() == Phase.QUEUED) {
                count++;
            }
        }
        return count;
    }

    private int countUserRunningJobs(Long userId) {
        if (userId == null) return 0;
        int count = 0;
        for (var entry : jobOwners.entrySet()) {
            if (!userId.equals(entry.getValue())) continue;
            QuickImportJobStatus job = jobs.get(entry.getKey());
            if (job == null) continue;
            Phase phase = job.getPhase();
            if (phase != Phase.QUEUED && phase != Phase.DONE && phase != Phase.FAILED) {
                count++;
            }
        }
        return count;
    }

    private void refreshQueuePositions() {
        int position = runningImports.get() + 1;
        for (PendingImport pending : pendingQueue) {
            QuickImportJobStatus job = jobs.get(pending.jobId());
            if (job != null && job.getPhase() == Phase.QUEUED) {
                jobs.put(pending.jobId(), job.toBuilder()
                        .queuePosition(position)
                        .message(null)
                        .messageKey(null)
                        .messageArgs(null)
                        .percent(0)
                        .activeSlotsUsed(runningImports.get())
                        .build());
                notifyJobUpdate(pending.jobId());
            }
            position++;
        }
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
        return repoUrl.length() > 48 ? repoUrl.substring(0, 45) + "\u2026" : repoUrl;
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
