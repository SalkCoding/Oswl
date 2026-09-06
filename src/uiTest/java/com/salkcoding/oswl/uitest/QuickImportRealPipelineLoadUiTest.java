package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real multi-stage pipeline load measurement — the two DoD points the earlier stubbed-clone load
 * test ({@link QuickImportLoadUiTest}) explicitly disclosed as unmeasurable at that speed: peak
 * HikariCP active connections and general web-request responsiveness while imports are in flight.
 *
 * <p>What makes this different from the stubbed version: every job here goes through a real
 * {@code git clone} against {@link LocalSmartHttpGitServer}, a loopback git-http-backend instance
 * with a configurable per-request delay, so the pipeline actually occupies wall-clock time instead
 * of failing in under a millisecond. {@code GitCloneExecutor} itself is untouched — the rewrite
 * from its hardcoded {@code https://} to the loopback server's plain {@code http://} happens via
 * a {@code GIT_CONFIG_GLOBAL} rewrite rule set for the whole test JVM (see the {@code uiTest} task
 * in build.gradle), so every {@code git} subprocess it spawns picks it up automatically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "oswl.airgapped.enabled=true")
@ActiveProfiles("uitest")
class QuickImportRealPipelineLoadUiTest {

    private static final String TEST_EMAIL = "test@test.com";
    private static final String TEST_PASSWORD = "1q2w3e4r";

    @Autowired
    private QuickImportService quickImportService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserVcsConnectionRepository vcsConnectionRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService enrichment;

    @LocalServerPort
    private int port;

    private static Path workDir;
    private static LocalSmartHttpGitServer gitServer;
    private static Path gitConfigGlobalFile;

    @BeforeAll
    static void startGitServer() throws Exception {
        String gitConfigGlobal = System.getenv("GIT_CONFIG_GLOBAL");
        assertThat(gitConfigGlobal)
                .withFailMessage("GIT_CONFIG_GLOBAL is not set for this JVM — see the uiTest task in build.gradle")
                .isNotBlank();
        gitConfigGlobalFile = Path.of(gitConfigGlobal);

        workDir = Files.createTempDirectory("oswl-d5-real-pipeline-");
        // 150ms per CGI round trip (info/refs + upload-pack ≈ 2 requests/clone) gives each clone
        // real wall-clock duration to observe, without making a 100-job burst take minutes.
        gitServer = LocalSmartHttpGitServer.start(workDir, 150, java.util.Map.of("package.json",
                "{\"name\":\"oswl-load-test\",\"version\":\"1.0.0\",\"dependencies\":{\"performance-fixture\":\"1.0.0\"}}",
                "package-lock.json", "{\"name\":\"oswl-load-test\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"packages\":{\"\":{\"name\":\"oswl-load-test\",\"version\":\"1.0.0\"},\"node_modules/performance-fixture\":{\"version\":\"1.0.0\"}}}"));
        gitServer.writeGitConfigGlobalRewrite(gitConfigGlobalFile);
    }

    @AfterAll
    static void stopGitServer() throws IOException {
        if (gitServer != null) {
            gitServer.close();
        }
        if (gitConfigGlobalFile != null) {
            Files.deleteIfExists(gitConfigGlobalFile);
        }
        if (workDir != null) {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("Real clone+parse+ingest pipeline under 20/50/100 concurrent imports — HikariCP peak and web-request latency")
    void realPipelineLoadMeasuresConnectionPoolAndWebResponsiveness() throws Exception {
        ensureTestAdmin();
        HttpClient httpClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        String csrfToken = loginAsTestAdmin(httpClient);

        StringBuilder report = new StringBuilder();
        report.append("Real pipeline (clone via loopback git-http-backend, 150ms/request delay)\n\n");
        report.append("Single-machine H2; real clone/parse/ingest with one dependency. Advisory clients use empty offline snapshots (no upstream HTTP or vulnerability coverage). DONE means scan ready, not AI completion. Process restart is not measured.\n");

        for (int scale : List.of(20, 50, 100)) {
            report.append(measureBurst(scale, httpClient, csrfToken));
        }

        Path dir = Path.of("build", "reports", "load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("quick-import-real-pipeline-load.txt"), report.toString());
    }

    @Test void delayedEnrichmentKeepsUserAdmissionAndCancellationSeparate() throws Exception {
        var enrichmentTarget = org.springframework.test.util.AopTestUtils.<com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService>getUltimateTargetObject(enrichment);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(enrichmentTarget).enrich(org.mockito.ArgumentMatchers.anyLong());
        long firstOwner = ensureSelfHostedVcsConnection(1001);
        long secondOwner = ensureSelfHostedVcsConnection(1002);
        java.util.Map<String, Long> owners = new java.util.LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            for (int i = 0; i < 3; i++) owners.put(quickImportService.startImport(
                    "https://" + gitServer.host() + "/slow/first-" + i + ".git", null, firstOwner), firstOwner);
            assertThat(entered.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long claimedDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (owners.keySet().stream().anyMatch(id -> quickImportService.getJobStatus(id, firstOwner).getPhase() == QuickImportJobStatus.Phase.QUEUED)
                    && System.nanoTime() < claimedDeadline) Thread.sleep(25);
            // The admission setting counts queued jobs; the three occupied worker slots are separate.
            for (int i = 0; i < 3; i++) owners.put(quickImportService.startImport(
                    "https://" + gitServer.host() + "/slow/first-queued-" + i + ".git", null, firstOwner), firstOwner);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> quickImportService.startImport(
                    "https://" + gitServer.host() + "/slow/rejected.git", null, firstOwner))
                    .isInstanceOf(com.salkcoding.oswl.exception.QuickImportQueueFullException.class);
            for (int i = 0; i < 3; i++) owners.put(quickImportService.startImport(
                    "https://" + gitServer.host() + "/slow/second-" + i + ".git", null, secondOwner), secondOwner);
            String running = owners.keySet().stream().filter(id -> owners.get(id).equals(firstOwner))
                    .filter(id -> quickImportService.getJobStatus(id, firstOwner).getScanResultId() != null).findFirst().orElseThrow();
            String queued = owners.keySet().stream().filter(id -> owners.get(id).equals(secondOwner))
                    .filter(id -> quickImportService.getJobStatus(id, secondOwner).getPhase() == QuickImportJobStatus.Phase.QUEUED).findFirst().orElseThrow();
            assertThat(quickImportService.cancelJob(running, firstOwner)).isTrue();
            assertThat(quickImportService.cancelJob(queued, secondOwner)).isTrue();
            release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            List<QuickImportJobStatus> states;
            do {
                states = owners.entrySet().stream().map(e -> quickImportService.getJobStatus(e.getKey(), e.getValue())).toList();
                if (states.stream().allMatch(s -> s.getPhase() == QuickImportJobStatus.Phase.DONE || s.getPhase() == QuickImportJobStatus.Phase.FAILED)) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            long done = states.stream().filter(s -> s.getPhase() == QuickImportJobStatus.Phase.DONE).count();
            long canceled = states.stream().filter(s -> QuickImportMessageKeys.CANCELED.equals(s.getMessageKey())).count();
            assertThat(done).isEqualTo(7);
            assertThat(canceled).isEqualTo(2);
            assertThat(quickImportService.getJobStatus(queued, secondOwner).getScanResultId()).isNull();
            Path report = Path.of("build/reports/load/delayed-enrichment-cancel.txt");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "Injected wait at enrichment entry; real clone/parse/ingest, offline advisory fallback.\n"
                    + "owners=2 accepted=9 admission-rejected=1 done=7 canceled=2 failed=0 elapsed_ms=" + (System.nanoTime()-started)/1_000_000 + "\n"
                    + "Running cancellation preserves its already-ingested scan; queued cancellation has no scan. Not a real upstream outage or process restart.\n");
        } finally {
            release.countDown();
            // Let any remaining jobs release their durable slots even after a failed assertion.
            owners.forEach((id, owner) -> quickImportService.cancelJob(id, owner));
            org.mockito.Mockito.reset(enrichmentTarget);
        }
    }

    private String measureBurst(int scale, HttpClient httpClient, String csrfToken) throws Exception {
        long userId = ensureSelfHostedVcsConnection(scale);

        List<String> urls = new ArrayList<>(scale);
        for (int i = 0; i < scale; i++) {
            urls.add("https://" + gitServer.host() + "/loadtest/repo-" + scale + "-" + i + ".git");
        }

        HikariPoolMXBean pool = dataSource instanceof HikariDataSource h ? h.getHikariPoolMXBean() : null;
        AtomicInteger peakActiveConnections = new AtomicInteger(0);
        AtomicInteger peakPendingConnections = new AtomicInteger(0);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread poolSampler = pool == null ? null : Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                peakActiveConnections.updateAndGet(prev -> Math.max(prev, pool.getActiveConnections()));
                peakPendingConnections.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });

        List<Long> webResponseTimesMs = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger webRequestFailures = new AtomicInteger(0);
        Thread webProbe = Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                long probeStart = System.nanoTime();
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl() + "/projects"))
                            .GET().timeout(Duration.ofSeconds(10)).build();
                    HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
                    if (resp.statusCode() == 200) {
                        webResponseTimesMs.add((System.nanoTime() - probeStart) / 1_000_000);
                    } else {
                        webRequestFailures.incrementAndGet();
                    }
                } catch (Exception e) {
                    webRequestFailures.incrementAndGet();
                }
            }
        });

        Instant start = Instant.now();
        List<String> jobIds;
        try {
        jobIds = quickImportService.startBatchImport(urls, userId);
        assertThat(jobIds).hasSize(scale);

        Duration timeout = Duration.ofSeconds(180);
        Instant deadline = start.plus(timeout);
        int terminalCount = 0;
        while (Instant.now().isBefore(deadline)) {
            terminalCount = (int) jobIds.stream()
                    .map(id -> quickImportService.getJobStatus(id, userId))
                    .filter(s -> s != null && (s.getPhase() == QuickImportJobStatus.Phase.DONE
                            || s.getPhase() == QuickImportJobStatus.Phase.FAILED))
                    .count();
            if (terminalCount == scale) break;
            Thread.sleep(50);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        List<QuickImportJobStatus> finalStates = jobIds.stream()
                .map(id -> quickImportService.getJobStatus(id, userId)).toList();
        long succeeded = finalStates.stream().filter(s -> s != null && s.getPhase() == QuickImportJobStatus.Phase.DONE).count();
        long canceled = finalStates.stream().filter(s -> s != null && QuickImportMessageKeys.CANCELED.equals(s.getMessageKey())).count();
        long failed = finalStates.stream().filter(s -> s != null && s.getPhase() == QuickImportJobStatus.Phase.FAILED
                && !QuickImportMessageKeys.CANCELED.equals(s.getMessageKey())).count();
        List<Long> waits = finalStates.stream().filter(s -> s != null && s.getRunningSinceEpochMs() != null && s.getStartedAtEpochMs() != null)
                .map(s -> Math.max(0, s.getRunningSinceEpochMs() - s.getStartedAtEpochMs())).sorted().toList();

        sampling.set(false);
        if (poolSampler != null) poolSampler.join(2000);
        webProbe.join(11_000);

        String outcome = "scale=%d accepted=%d done=%d failed=%d canceled=%d unfinished=%d elapsed=%dms successful-throughput=%.3f/s queue-wait-n=%d p50=%dms p95=%dms peak-active=%d peak-pending=%d%n".formatted(
                scale, jobIds.size(), succeeded, failed, canceled, scale - terminalCount, elapsed.toMillis(),
                succeeded / Math.max(.001, elapsed.toMillis() / 1000.0), waits.size(),
                waits.isEmpty() ? 0 : percentile(waits,50), waits.isEmpty() ? 0 : percentile(waits,95),
                peakActiveConnections.get(), peakPendingConnections.get());
        Path outcomes = Path.of("build/reports/load/quick-import-outcomes.txt");
        Files.createDirectories(outcomes.getParent());
        Files.writeString(outcomes, outcome, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);

        int completionCount = terminalCount;
        assertThat(completionCount)
                .withFailMessage("%d of %d jobs never reached a terminal state within %s",
                        scale - completionCount, scale, timeout)
                .isEqualTo(scale);
        assertThat(succeeded).as("real clone/parse/ingest success (FAILED is not successful throughput)").isEqualTo(scale);

        List<Long> sorted = new ArrayList<>(webResponseTimesMs);
        sorted.sort(null);
        String webStats = sorted.isEmpty() ? "n=0 (no samples captured in this window)"
                : "n=%d p50=%dms p95=%dms max=%dms failures=%d".formatted(
                        sorted.size(), percentile(sorted, 50), percentile(sorted, 95),
                        sorted.get(sorted.size() - 1), webRequestFailures.get());

        return outcome + "scale=%d: terminal %d/%d in %dms, peak HikariCP active=%s, GET /projects: %s\n".formatted(
                scale, completionCount, scale, elapsed.toMillis(),
                pool == null ? "n/a" : String.valueOf(peakActiveConnections.get()), webStats);
        } finally {
            sampling.set(false);
            if (poolSampler != null) poolSampler.join(2000);
            webProbe.join(11_000);
        }
    }

    private static long percentile(List<Long> sorted, int pct) {
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(pct / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    /**
     * @return the actual (DB-assigned) id of the load-test owner user for this scale — never the
     * caller's requested value, since {@link User#getId()} is auto-generated and cannot be pinned
     * to an arbitrary long. Callers must use the returned id, not invent their own.
     */
    private long ensureSelfHostedVcsConnection(int scale) {
        String email = "d5-loadtest-" + scale + "@test.local";
        User owner = userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("unused"))
                .displayName("D5 Load Test User (scale " + scale + ")")
                .isSystemAdmin(false)
                .enabled(true)
                .build()));
        long userId = owner.getId();

        boolean hasConnection = vcsConnectionRepository.findByUserIdAndActiveTrue(userId).stream()
                .anyMatch(c -> gitServer.host().equals(c.getServerUrl().replaceAll("https?://", "")));
        if (hasConnection) {
            return userId;
        }
        vcsConnectionRepository.save(UserVcsConnection.builder()
                .user(owner)
                .provider(VcsProvider.GITHUB)
                .serverUrl("https://" + gitServer.host())
                .accessTokenEncrypted(encryptionService.encrypt("unused-loopback-token"))
                .vcsUsername("d5-loadtest")
                .active(true)
                .build());
        return userId;
    }

    private void ensureTestAdmin() {
        if (userRepository.existsByEmail(TEST_EMAIL)) {
            return;
        }
        userRepository.save(User.builder()
                .email(TEST_EMAIL)
                .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
                .displayName("test")
                .isSystemAdmin(true)
                .enabled(true)
                .build());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Logs in through the real /login form with a raw {@link HttpClient} (no browser): fetches the
     * page for its CSRF hidden field and session cookie, then posts credentials. Assumes
     * TwoFaMode.DISABLED, the fresh-instance default {@link UiTestBase} also relies on.
     */
    private String loginAsTestAdmin(HttpClient httpClient) throws Exception {
        HttpRequest getLogin = HttpRequest.newBuilder(URI.create(baseUrl() + "/login")).GET().build();
        HttpResponse<String> loginPage = httpClient.send(getLogin, HttpResponse.BodyHandlers.ofString());
        Matcher m = Pattern.compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"").matcher(loginPage.body());
        assertThat(m.find()).withFailMessage("Could not find _csrf hidden field on /login").isTrue();
        String csrfToken = m.group(1);

        String form = "email=" + java.net.URLEncoder.encode(TEST_EMAIL, StandardCharsets.UTF_8)
                + "&password=" + java.net.URLEncoder.encode(TEST_PASSWORD, StandardCharsets.UTF_8)
                + "&_csrf=" + java.net.URLEncoder.encode(csrfToken, StandardCharsets.UTF_8);
        HttpRequest postLogin = HttpRequest.newBuilder(URI.create(baseUrl() + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<Void> loginResp = httpClient.send(postLogin, HttpResponse.BodyHandlers.discarding());
        assertThat(loginResp.statusCode())
                .withFailMessage("Login POST returned %d, expected a redirect (302)", loginResp.statusCode())
                .isEqualTo(302);
        return csrfToken;
    }
}
