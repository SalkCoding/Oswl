package com.salkcoding.oswl.service.manifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Single source of truth for which project files the CLI should upload and
 * which directories {@link com.salkcoding.oswl.service.DependencyManifestParserService}
 * skips when walking manifests.
 *
 * <p>Serialized to {@code /scripts/manifest-rules.json} for shell clients.</p>
 */
public final class ManifestCollectRules {

    private ManifestCollectRules() {}

    /** Path segment names to skip when walking a project tree (same as parser walk). */
    public static final Set<String> SKIP_DIRS = Set.of(
            ".git", "node_modules", "vendor", "target", ".gradle", "__pycache__",
            ".venv", "venv", ".tox", "dist", ".cargo", "build", "out", "bin",
            "obj", ".idea", ".dart_tool", "bower_components", "generated");

    /** Basenames collected for server-side parsing. */
    public static final Set<String> EXACT_FILE_NAMES = Set.of(
            "pom.xml",
            "build.gradle", "build.gradle.kts",
            "settings.gradle", "settings.gradle.kts",
            "gradle.properties",
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "package.json",
            "poetry.lock", "uv.lock", "Pipfile.lock", "requirements.txt", "pyproject.toml",
            "Cargo.lock", "Cargo.toml",
            "go.sum", "go.mod",
            "packages.lock.json", "packages.config", "Directory.Packages.props",
            "Gemfile.lock",
            "mvnw", "mvnw.cmd", "gradlew", "gradlew.bat",
            "global.json", "nuget.config");

    /** File suffixes (case-sensitive basename) collected anywhere in the tree. */
    public static final Set<String> FILE_SUFFIXES = Set.of(
            ".csproj", ".props", ".sln", ".versions.toml");

    /** Relative path prefixes (forward slashes) always collected. */
    public static final Set<String> PATH_PREFIXES = Set.of(
            ".mvn/wrapper/", "gradle/wrapper/");

    /** Under {@code buildSrc/}, collect sources used by Gradle BOM variable resolution. */
    public static final Set<String> BUILD_SRC_SUFFIXES = Set.of(".kt", ".java");

    public static final int RULES_VERSION = 1;

    /**
     * Returns whether a file at {@code relativePath} (POSIX separators) should be included
     * in a CLI manifest archive for server-side parsing.
     */
    public static boolean shouldCollect(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String norm = relativePath.replace('\\', '/').replaceAll("^/+", "");
        if (containsSkipDir(norm)) {
            return false;
        }
        String base = baseName(norm);
        if (EXACT_FILE_NAMES.contains(base)) {
            return true;
        }
        for (String suffix : FILE_SUFFIXES) {
            if (base.endsWith(suffix)) {
                return true;
            }
        }
        for (String prefix : PATH_PREFIXES) {
            if (norm.startsWith(prefix)) {
                return true;
            }
        }
        if (norm.startsWith("buildSrc/")) {
            for (String suffix : BUILD_SRC_SUFFIXES) {
                if (base.endsWith(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean containsSkipDir(String relativePath) {
        String norm = relativePath.replace('\\', '/');
        for (String segment : norm.split("/")) {
            if (SKIP_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists relative paths (POSIX) under {@code projectRoot} that should be uploaded by the CLI.
     */
    public static List<String> collectRelativePaths(Path projectRoot) throws java.io.IOException {
        Set<String> rels = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String rel = projectRoot.relativize(file).toString().replace('\\', '/');
                if (shouldCollect(rel)) {
                    rels.add(rel);
                }
            });
        }
        return List.copyOf(rels);
    }

    private static String baseName(String norm) {
        int slash = norm.lastIndexOf('/');
        return slash >= 0 ? norm.substring(slash + 1) : norm;
    }

    /** DTO shape for {@code manifest-rules.json}. */
    public record RulesJson(
            int version,
            List<String> skipDirs,
            List<String> exactFileNames,
            List<String> fileSuffixes,
            List<String> pathPrefixes,
            List<String> buildSrcSuffixes) {

        public static RulesJson current() {
            return new RulesJson(
                    RULES_VERSION,
                    sorted(SKIP_DIRS),
                    sorted(EXACT_FILE_NAMES),
                    sorted(FILE_SUFFIXES),
                    sorted(PATH_PREFIXES),
                    sorted(BUILD_SRC_SUFFIXES));
        }

        private static List<String> sorted(Set<String> set) {
            return set.stream().sorted().toList();
        }
    }
}
