package com.salkcoding.oswl.support;

import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/** Optional external checkout checks must distinguish missing inputs from parser failures. */
public final class ExternalVerificationFixture {
    private ExternalVerificationFixture() {}

    public static List<String> readableManifests(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        try {
            return ManifestCollectRules.collectRelativePaths(root).stream()
                    .filter(relative -> Files.isReadable(root.resolve(relative)))
                    .filter(relative -> {
                        try { return Files.size(root.resolve(relative)) > 0; }
                        catch (IOException e) { return false; }
                    }).toList();
        } catch (IOException e) { return List.of(); }
    }

    public static void require(Path root, String description, Predicate<String> requiredManifest) {
        Assumptions.assumeTrue(readableManifests(root).stream().anyMatch(requiredManifest),
                () -> "Skip: external fixture " + root + " needs " + description
                        + "; an empty or .git-only directory is not a prepared checkout.");
    }

    public static boolean hasParseInput(Path root) {
        return readableManifests(root).stream().anyMatch(name -> {
            String base = Path.of(name).getFileName().toString();
            return !base.equals(".gitmodules") && !base.startsWith("gradlew") && !base.startsWith("mvnw")
                    && !name.contains("wrapper/") && !base.equals("global.json") && !base.equals("nuget.config");
        });
    }
}
