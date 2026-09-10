package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
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
     * A handful of well-known PyPI distribution names whose top-level importable module doesn't
     * follow the normal "lowercase, hyphens to underscores" rule — either because it predates PEP
     * naming conventions (Pillow -> PIL, PyYAML -> yaml), because the PyPI name is a suffixed
     * variant of a shared top-level namespace (opencv-contrib-python -> cv2, google-cloud-storage
     * -> google), or because the two names have historically diverged (beautifulsoup4 -> bs4).
     * Not exhaustive — this only needs to cover common cases; anything missing here still gets a
     * (possibly wrong) guess from the normal-rule fallback, and a wrong guess just costs an UNKNOWN
     * or a missed REACHABLE, not an incorrect one, since evidence is only recorded on an actual
     * source match.
     */
    private static final Map<String, String> PYPI_IMPORT_NAME_OVERRIDES = Map.ofEntries(
            Map.entry("pillow", "PIL"),
            Map.entry("pyyaml", "yaml"),
            Map.entry("beautifulsoup4", "bs4"),
            Map.entry("scikit-learn", "sklearn"),
            Map.entry("opencv-python", "cv2"),
            Map.entry("opencv-contrib-python", "cv2"),
            Map.entry("opencv-python-headless", "cv2"),
            Map.entry("protobuf", "google"),
            Map.entry("grpcio", "grpc"),
            Map.entry("python-dotenv", "dotenv"),
            Map.entry("python-dateutil", "dateutil"),
            Map.entry("python-jose", "jose"),
            Map.entry("python-multipart", "multipart"),
            Map.entry("python-json-logger", "pythonjsonlogger"),
            Map.entry("msgpack-python", "msgpack"),
            Map.entry("attrs", "attr"),
            Map.entry("deprecated", "deprecated"),
            Map.entry("pyjwt", "jwt"),
            Map.entry("pymysql", "pymysql"),
            Map.entry("psycopg2-binary", "psycopg2"),
            Map.entry("mysql-connector-python", "mysql"),
            Map.entry("google-cloud-storage", "google"),
            Map.entry("google-cloud-bigquery", "google"),
            Map.entry("google-api-python-client", "googleapiclient"),
            Map.entry("websocket-client", "websocket"),
            Map.entry("pycryptodome", "Crypto"),
            Map.entry("djangorestframework", "rest_framework"),
            Map.entry("django-rest-framework", "rest_framework"));

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

    /**
     * Returns candidate top-level import/require names for the given component — the source-level
     * analog of {@link #map}, used by {@link SourceReferenceAnalyzer} for ecosystems that have no
     * compiled bytecode to inspect. Unlike Maven/Gradle's hierarchical class prefixes, Python and
     * npm packages are referenced by a single top-level module/package name, so this returns
     * whole candidate names rather than prefixes.
     *
     * @return non-null set of candidate names, may be empty for ecosystems this doesn't cover
     */
    public static Set<String> mapImportNames(ScanComponent component) {
        if (component == null || component.getLibrary() == null) {
            return Set.of();
        }
        return mapImportNames(component.getLibrary().getEcosystem(), component.getLibrary().getName());
    }

    public static Set<String> mapImportNames(String ecosystem, String name) {
        if (ecosystem == null || name == null || name.isBlank()) {
            return Set.of();
        }

        return switch (ecosystem.toUpperCase(Locale.ROOT)) {
            case "PYPI" -> mapPypi(name);
            case "NPM" -> mapNpm(name);
            default -> Set.of();
        };
    }

    private static Set<String> mapPypi(String distributionName) {
        String trimmed = distributionName.trim();
        if (trimmed.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        String override = PYPI_IMPORT_NAME_OVERRIDES.get(trimmed.toLowerCase(Locale.ROOT));
        if (override != null) {
            names.add(override);
        }
        // Normal-rule guess: PyPI distribution names are conventionally lowercased with hyphens,
        // while the importable module is lowercase with underscores (or, often, identical).
        names.add(trimmed.toLowerCase(Locale.ROOT).replace('-', '_'));
        names.add(trimmed);
        return names;
    }

    private static Set<String> mapNpm(String packageName) {
        String trimmed = packageName.trim();
        // npm package names are, without exception, exactly what's imported/required — including
        // the "@scope/name" form for scoped packages — so no name-mangling guesswork is needed.
        return trimmed.isEmpty() ? Set.of() : Set.of(trimmed);
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
