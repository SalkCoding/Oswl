package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
        gitServer = LocalSmartHttpGitServer.start(workDir, 150);
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

        for (int scale : List.of(20, 50, 100)) {
            report.append(measureBurst(scale, httpClient, csrfToken));
        }

        Path dir = Path.of("build", "reports", "load");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("quick-import-real-pipeline-load.txt"), report.toString());
    }

    private String measureBurst(int scale, HttpClient httpClient, String csrfToken) throws Exception {
        long userId = ensureSelfHostedVcsConnection(scale);

        List<String> urls = new ArrayList<>(scale);
        for (int i = 0; i < scale; i++) {
            urls.add("https://" + gitServer.host() + "/loadtest/repo-" + scale + "-" + i + ".git");
        }

        HikariPoolMXBean pool = dataSource instanceof HikariDataSource h ? h.getHikariPoolMXBean() : null;
        AtomicInteger peakActiveConnections = new AtomicInteger(0);
        AtomicBoolean sampling = new AtomicBoolean(true);
        Thread poolSampler = pool == null ? null : Thread.ofVirtual().start(() -> {
            while (sampling.get()) {
                peakActiveConnections.updateAndGet(prev -> Math.max(prev, pool.getActiveConnections()));
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
        List<String> jobIds = quickImportService.startBatchImport(urls, userId);
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

        sampling.set(false);
        if (poolSampler != null) poolSampler.join(2000);
        webProbe.join(11_000);

        int completionCount = terminalCount;
        assertThat(completionCount)
                .withFailMessage("%d of %d jobs never reached a terminal state within %s",
                        scale - completionCount, scale, timeout)
                .isEqualTo(scale);

        List<Long> sorted = new ArrayList<>(webResponseTimesMs);
        sorted.sort(null);
        String webStats = sorted.isEmpty() ? "n=0 (no samples captured in this window)"
                : "n=%d p50=%dms p95=%dms max=%dms failures=%d".formatted(
                        sorted.size(), percentile(sorted, 50), percentile(sorted, 95),
                        sorted.get(sorted.size() - 1), webRequestFailures.get());

        return "scale=%d: completed %d/%d in %dms, peak HikariCP active=%s, GET /projects: %s\n".formatted(
                scale, completionCount, scale, elapsed.toMillis(),
                pool == null ? "n/a" : String.valueOf(peakActiveConnections.get()), webStats);
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
