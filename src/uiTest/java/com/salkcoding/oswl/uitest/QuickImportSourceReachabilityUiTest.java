package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.enums.Reachability;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that {@link com.salkcoding.oswl.service.reachability.SourceReachabilityService}
 * actually finds real REACHABLE components through the real Quick Import pipeline — parsing
 * success alone doesn't prove detection works (the exact class of gap past parsing-only checks missed), so this clones a real fixture repo with both a manifest AND real source
 * that imports one of its own dependencies, and asserts the persisted verdict.
 *
 * <p>Source checkout is enabled independently; repository build execution remains disabled.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("uitest")
@TestPropertySource(properties = {"oswl.quick-import.allow-build-exec=false", "oswl.clone.include-source=true"})
class QuickImportSourceReachabilityUiTest {

    @Autowired
    private QuickImportService quickImportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserVcsConnectionRepository vcsConnectionRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ScanComponentRepository scanComponentRepository;

    private static java.nio.file.Path workDir;
    private static LocalSmartHttpGitServer gitServer;
    private static java.nio.file.Path gitConfigGlobalFile;

    @BeforeAll
    static void startGitServer() throws Exception {
        String gitConfigGlobal = System.getenv("GIT_CONFIG_GLOBAL");
        assertThat(gitConfigGlobal)
                .withFailMessage("GIT_CONFIG_GLOBAL is not set for this JVM — see the uiTest task in build.gradle")
                .isNotBlank();
        gitConfigGlobalFile = java.nio.file.Path.of(gitConfigGlobal);

        workDir = Files.createTempDirectory("oswl-a2-source-reach-");
        Map<String, String> files = Map.of(
                "requirements.txt", "requests==2.31.0\n",
                "app.py", "import requests\n\ndef fetch():\n    return requests.get('https://example.invalid')\n",
                "package.json", "{\"name\":\"a2-fixture\",\"version\":\"1.0.0\",\"dependencies\":{\"lodash\":\"^4.17.21\"}}\n",
                "index.js", "const _ = require('lodash');\n\nmodule.exports = () => _.chunk([1, 2, 3], 2);\n");
        gitServer = LocalSmartHttpGitServer.start(workDir, 0, files);
        gitServer.writeGitConfigGlobalRewrite(gitConfigGlobalFile);
    }

    @AfterAll
    static void stopGitServer() throws IOException {
        if (gitServer != null) gitServer.close();
        if (gitConfigGlobalFile != null) Files.deleteIfExists(gitConfigGlobalFile);
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
    @DisplayName("Real clone with Python+JS source finds REACHABLE for both an imported PyPI and npm dependency")
    void realCloneFindsReachableComponents() throws Exception {
        long userId = ensureSelfHostedVcsConnection();
        String repoUrl = "https://" + gitServer.host() + "/a2fixture/reachability-repo.git";

        String jobId = quickImportService.startImport(repoUrl, null, userId);
        assertThat(jobId).isNotNull();

        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        QuickImportJobStatus status = null;
        while (Instant.now().isBefore(deadline)) {
            status = quickImportService.getJobStatus(jobId, userId);
            if (status != null && (status.getPhase() == QuickImportJobStatus.Phase.DONE
                    || status.getPhase() == QuickImportJobStatus.Phase.FAILED)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(status).isNotNull();
        assertThat(status.getPhase())
                .withFailMessage("Job did not reach DONE: %s", status)
                .isEqualTo(QuickImportJobStatus.Phase.DONE);

        List<ScanComponent> components = scanComponentRepository.findByScanResultId(status.getScanResultId());
        assertThat(components).isNotEmpty();

        Optional<ScanComponent> requests = components.stream()
                .filter(c -> "requests".equalsIgnoreCase(c.getLibrary().getName())).findFirst();
        assertThat(requests).withFailMessage("requests component not found among: %s",
                components.stream().map(c -> c.getLibrary().getName()).toList()).isPresent();
        assertThat(requests.get().getReachability()).isEqualTo(Reachability.REACHABLE);
        assertThat(requests.get().getReachabilityEvidence()).contains("app.py").contains("requests");

        Optional<ScanComponent> lodash = components.stream()
                .filter(c -> "lodash".equalsIgnoreCase(c.getLibrary().getName())).findFirst();
        assertThat(lodash).withFailMessage("lodash component not found among: %s",
                components.stream().map(c -> c.getLibrary().getName()).toList()).isPresent();
        assertThat(lodash.get().getReachability()).isEqualTo(Reachability.REACHABLE);
        assertThat(lodash.get().getReachabilityEvidence()).contains("index.js").contains("lodash");
    }

    private long ensureSelfHostedVcsConnection() {
        String email = "a2-fixture-owner@test.local";
        User owner = userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode("unused"))
                .displayName("A2 Fixture Owner")
                .isSystemAdmin(false)
                .enabled(true)
                .build()));
        long userId = owner.getId();
        boolean hasConnection = vcsConnectionRepository.findByUserIdAndActiveTrue(userId).stream()
                .anyMatch(c -> gitServer.host().equals(c.getServerUrl().replaceAll("https?://", "")));
        if (!hasConnection) {
            vcsConnectionRepository.save(UserVcsConnection.builder()
                    .user(owner)
                    .provider(VcsProvider.GITHUB)
                    .serverUrl("https://" + gitServer.host())
                    .accessTokenEncrypted(encryptionService.encrypt("unused-loopback-token"))
                    .vcsUsername("a2-fixture")
                    .active(true)
                    .build());
        }
        return userId;
    }
}
