package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.manifest.ManifestCollectArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class ManifestParserBoundaryTest {
    @TempDir Path root;
    private final DependencyManifestParserService parser = new DependencyManifestParserService(
            new MavenBomVersionResolver(), new CondaPypiMappingService());

    @Test void mixedRepositoryAndCliArchiveHaveTheSameCoordinates() throws Exception {
        Files.writeString(root.resolve("requirements.txt"), "requests==2.32.0\n");
        Files.writeString(root.resolve("composer.lock"), "{\"content-hash\":\"fixture\",\"packages\":[{\"name\":\"vendor/pkg\",\"version\":\"v1.2.3\",\"license\":[\"MIT\"]}]}");
        Files.writeString(root.resolve("package-lock.json"), "{\"lockfileVersion\":3,\"packages\":{\"node_modules/example\":{\"version\":\"2.0.0\"}}}");
        Files.createDirectories(root.resolve("node_modules/ignored"));
        Files.writeString(root.resolve("node_modules/ignored/requirements.txt"), "ignored==9.9.9\n");
        var direct = parser.parseDependencies(root, "fixture");
        var archive = new ManifestCollectArchiveService();
        Path zip = archive.zipProjectManifests(root);
        Path extracted = archive.extractZipToTemp(zip);
        try {
            var uploaded = parser.parseDependencies(extracted, "fixture");
            assertThat(keys(direct.components())).containsExactlyInAnyOrder(
                    "PYPI:requests:2.32.0", "COMPOSER:vendor/pkg:1.2.3", "NPM:example:2.0.0");
            assertThat(keys(uploaded.components())).isEqualTo(keys(direct.components()));
        } finally {
            Files.deleteIfExists(zip);
            try (var paths = Files.walk(extracted)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private Set<String> keys(List<ScanPayload.ComponentPayload> components) {
        return components.stream().map(c -> c.getEcosystem() + ":" + c.getName() + ":" + c.getVersion()).collect(Collectors.toSet());
    }
}
