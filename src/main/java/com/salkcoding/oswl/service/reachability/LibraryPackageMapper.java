package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.entity.ScanComponent;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Derives likely internal class-name prefixes for a scanned component so the
 * call-graph analyzer can check whether the project bytecode references it.
 *
 * <p>For Maven/Gradle coordinates such as {@code org.springframework:spring-core},
 * the analyzer checks prefixes like {@code org/springframework/core/} and
 * {@code org/springframework/}. For ecosystems where package naming cannot be
 * inferred, an empty set is returned, which causes the reachability result to be
 * {@code UNKNOWN}.</p>
 */
@Slf4j
public final class LibraryPackageMapper {

    private LibraryPackageMapper() {
    }

    /**
     * Returns candidate internal class-name prefixes for the given component.
     *
     * @param component the scan component (must provide library name and ecosystem)
     * @return non-null set of prefixes ending with '/', may be empty
     */
    public static Set<String> map(ScanComponent component) {
        if (component == null || component.getLibrary() == null) {
            return Set.of();
        }
        String ecosystem = component.getLibrary().getEcosystem();
        String name = component.getLibrary().getName();
        if (ecosystem == null || name == null || name.isBlank()) {
            return Set.of();
        }

        return switch (ecosystem.toUpperCase(Locale.ROOT)) {
            case "MAVEN", "GRADLE" -> mapMavenOrGradle(name);
            default -> Set.of();
        };
    }

    private static Set<String> mapMavenOrGradle(String coordinate) {
        // Coordinate format: groupId:artifactId, e.g. org.springframework:spring-core
        String[] parts = coordinate.split(":");
        if (parts.length < 2) {
            return Set.of();
        }
        String groupId = parts[0].trim();
        String artifactId = parts[1].trim();
        if (groupId.isBlank() || artifactId.isBlank()) {
            return Set.of();
        }

        Set<String> prefixes = new HashSet<>();
        String groupPath = groupId.replace('.', '/');

        // Most specific: groupId/artifactId-with-dashes-kept/
        prefixes.add(groupPath + "/" + artifactId + "/");

        // ArtifactId often becomes a sub-package of the group, e.g. org/springframework/core
        // for spring-core. Add a prefix matching "groupId/artifactId-without-suffix/".
        String[] groupSegments = groupId.split("\\.");
        String lastGroupSegment = groupSegments[groupSegments.length - 1];
        if (artifactId.startsWith(lastGroupSegment + "-")) {
            String subPackage = artifactId.substring(lastGroupSegment.length() + 1).replace('-', '/');
            if (!subPackage.isBlank()) {
                prefixes.add(groupPath + "/" + subPackage + "/");
            }
        }

        // Fallback to the whole group path.
        prefixes.add(groupPath + "/");

        return prefixes;
    }
}
