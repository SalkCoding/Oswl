package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Live Bitbucket Cloud integration test.
 *
 * <p>Run manually with real credentials (never commit tokens):
 * <pre>
 *   set BITBUCKET_LIVE_TEST=true
 *   set BITBUCKET_EMAIL=you@example.com
 *   set BITBUCKET_WORKSPACE=salkcoding
 *   set BITBUCKET_TOKEN=ATATT...
 *   set BITBUCKET_REPO=test
 *   gradlew test --tests BitbucketCloudLiveIntegrationTest
 * </pre>
 */
@Tag(TestTags.LIVE)
@EnabledIfEnvironmentVariable(named = "BITBUCKET_LIVE_TEST", matches = "true")
@DisplayName("Bitbucket Cloud Live 통합 테스트")
class BitbucketCloudLiveIntegrationTest {

    private final BitbucketCloudClient client = new BitbucketCloudClient();
    private final GitCloneExecutor gitCloneExecutor = new GitCloneExecutor();

    @Test
    @DisplayName("live: validate → list repos → clone via GIT_ASKPASS")
    void live_validateListAndClone() throws Exception {
        String email = env("BITBUCKET_EMAIL");
        String workspace = env("BITBUCKET_WORKSPACE");
        String token = env("BITBUCKET_TOKEN");
        String repo = envOrDefault("BITBUCKET_REPO", "test");

        Assumptions.assumeTrue(email != null && workspace != null && token != null,
                "Set BITBUCKET_EMAIL, BITBUCKET_WORKSPACE, BITBUCKET_TOKEN");

        String vcsUsername = email + "|" + workspace;

        assertThatCode(() -> client.validateToken(vcsUsername, token))
                .as("VCS Connection token validation")
                .doesNotThrowAnyException();

        var repos = client.listRepositories(vcsUsername, token);
        assertThat(repos).as("Quick Import repo list").isNotEmpty();
        assertThat(repos.stream().anyMatch(r -> repo.equals(r.name()))).isTrue();

        var creds = client.cloneCredentials(vcsUsername, token);
        assertThat(creds.username()).isEqualTo("x-bitbucket-api-token-auth");

        Assumptions.assumeTrue(isGitAvailable(), "git not available — skipping clone step");
        Path tempDir = Files.createTempDirectory("oswl-bb-live-");
        try {
            String repoUrl = "https://bitbucket.org/" + workspace + "/" + repo + ".git";
            gitCloneExecutor.clone(repoUrl, creds, null, tempDir, "live-test");
            assertThat(Files.list(tempDir).findAny()).isPresent();
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String envOrDefault(String key, String defaultValue) {
        String v = env(key);
        return v != null ? v : defaultValue;
    }

    private static boolean isGitAvailable() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        }
    }
}
