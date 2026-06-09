package com.salkcoding.oswl.service.manifest;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.DependencyManifestParserService;
import com.salkcoding.oswl.service.MavenBomVersionResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies CLI manifest zip (via {@link ManifestCollectRules}) produces the same parse
 * result as a full project tree parse (Quick Import clone equivalent).
 */
@DisplayName("CLI manifest zip vs full-tree parse parity")
class ManifestCollectParityTest {

    private static final Path VERIFY_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "oswl-verify");

    static Stream<Path> verificationRepos() {
        if (!Files.isDirectory(VERIFY_ROOT)) {
            return Stream.empty();
        }
        try (Stream<Path> dirs = Files.list(VERIFY_ROOT)) {
            return dirs.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList()
                    .stream();
        } catch (Exception e) {
            return Stream.empty();
        }
    }

    private final DependencyManifestParserService parser =
            new DependencyManifestParserService(new MavenBomVersionResolver());
    private final ManifestCollectArchiveService archiveService = new ManifestCollectArchiveService();

  @ParameterizedTest(name = "{0}")
    @MethodSource("verificationRepos")
    @DisplayName("manifest zip parse matches full-tree parse")
    void manifestZip_matchesFullParse(Path repoDir, @TempDir Path work) throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(repoDir),
                "Skip: no clone at " + repoDir);

        String label = repoDir.getFileName().toString();
        var full = parser.parseDependencies(repoDir, label);

        Path zip = archiveService.zipProjectManifests(repoDir);
        Path extracted = work.resolve("extracted");
        Files.createDirectories(extracted);
        Path extractRoot = archiveService.extractZipToTemp(zip);
        try {
            var fromZip = parser.parseDependencies(extractRoot, label);

            Set<String> fullKeys = componentKeys(full.components());
            Set<String> zipKeys = componentKeys(fromZip.components());

            System.out.printf("[%s] full=%d zip=%d overlap=%d%n",
                    label, fullKeys.size(), zipKeys.size(),
                    fullKeys.stream().filter(zipKeys::contains).count());

            assertThat(zipKeys)
                    .as("CLI zip must not miss components from full parse (%s)", label)
                    .containsAll(fullKeys);
            assertThat(fromZip.components().size())
                    .as("component count for %s", label)
                    .isEqualTo(full.components().size());
            assertThat(fromZip.ecosystem()).isEqualTo(full.ecosystem());
        } finally {
            Files.deleteIfExists(zip);
            deleteRecursively(extractRoot);
        }
    }

    @Test
    @DisplayName("at least one verification repo is present locally")
    void verificationReposExist() {
        List<Path> repos = verificationRepos().toList();
        Assumptions.assumeTrue(!repos.isEmpty(),
                "Skip: clone repos under " + VERIFY_ROOT);
        assertThat(repos).isNotEmpty();
    }

    private static Set<String> componentKeys(List<ScanPayload.ComponentPayload> comps) {
        return comps.stream()
                .map(c -> (c.getEcosystem() != null ? c.getEcosystem() : "?") + "|"
                        + c.getName() + "|"
                        + (c.getVersion() != null ? c.getVersion() : ""))
                .collect(Collectors.toSet());
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
