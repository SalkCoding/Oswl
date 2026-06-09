package com.salkcoding.oswl.service;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates and patches dependency version strings in common manifest / lock files.
 */
final class DependencyManifestPatcher {

    private static final Set<String> MANIFEST_FILE_NAMES = Set.of(
            "pom.xml",
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "libs.versions.toml",
            "package.json",
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "requirements.txt",
            "pyproject.toml",
            "poetry.lock",
            "Pipfile",
            "Pipfile.lock",
            "go.mod",
            "go.sum",
            "Cargo.toml",
            "Cargo.lock",
            "Gemfile",
            "Gemfile.lock",
            "composer.json",
            "composer.lock",
            "pubspec.yaml",
            "pubspec.lock"
    );

    private DependencyManifestPatcher() {}

    static boolean isManifestPath(String path) {
        if (path == null || path.isBlank()) return false;
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return MANIFEST_FILE_NAMES.contains(name);
    }

    /**
     * @return patched file content, or empty if this file does not declare the library at {@code oldVersion}
     */
    static Optional<String> patch(String content, String path, String libName, String oldVersion, String newVersion) {
        if (content == null || content.isBlank() || oldVersion == null || newVersion == null) {
            return Optional.empty();
        }
        String fileName = fileName(path);
        if ("package.json".equals(fileName)) {
            Optional<String> npm = patchNpmJson(content, libName, oldVersion, newVersion);
            if (npm.isPresent()) return npm;
        }
        if ("package-lock.json".equals(fileName) || "npm-shrinkwrap.json".equals(fileName)) {
            Optional<String> lock = patchNpmLock(content, libName, oldVersion, newVersion);
            if (lock.isPresent()) return lock;
        }
        if ("pom.xml".equals(fileName)) {
            Optional<String> maven = patchMavenPom(content, libName, oldVersion, newVersion);
            if (maven.isPresent()) return maven;
        }
        if (fileName.endsWith(".gradle") || fileName.endsWith(".gradle.kts") || "libs.versions.toml".equals(fileName)) {
            Optional<String> gradle = patchGradleStyle(content, libName, oldVersion, newVersion);
            if (gradle.isPresent()) return gradle;
        }
        if ("requirements.txt".equals(fileName) || "pyproject.toml".equals(fileName) || "Pipfile".equals(fileName)) {
            Optional<String> py = patchPythonRequirement(content, libName, oldVersion, newVersion);
            if (py.isPresent()) return py;
        }
        if ("go.mod".equals(fileName)) {
            Optional<String> go = patchGoMod(content, libName, oldVersion, newVersion);
            if (go.isPresent()) return go;
        }
        return patchGeneric(content, libName, oldVersion, newVersion);
    }

    static String artifactId(String libName) {
        if (libName == null || libName.isBlank()) return "";
        int colon = libName.lastIndexOf(':');
        if (colon >= 0 && colon < libName.length() - 1) {
            String tail = libName.substring(colon + 1);
            // Maven coordinates group:artifact:version → use artifact segment
            if (libName.indexOf(':') != colon) {
                String mid = libName.substring(libName.indexOf(':') + 1, colon);
                if (!mid.isBlank()) return mid;
            }
            return tail;
        }
        return libName;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static Optional<String> patchNpmJson(String content, String libName, String oldVersion, String newVersion) {
        String pkg = artifactId(libName);
        Pattern pattern = Pattern.compile(
                "(\"" + Pattern.quote(pkg) + "\"\\s*:\\s*[\"'])([^\"']+)([\"'])",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (!matcher.find()) return Optional.empty();
        String versionSpec = matcher.group(2);
        if (!versionSpecContains(versionSpec, oldVersion)) return Optional.empty();
        String bumped = versionSpec.replace(oldVersion, newVersion);
        StringBuffer sb = new StringBuffer();
        matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1) + bumped + matcher.group(3)));
        matcher.appendTail(sb);
        return Optional.of(sb.toString());
    }

    private static Optional<String> patchNpmLock(String content, String libName, String oldVersion, String newVersion) {
        String pkg = artifactId(libName);
        String quotedPkg = Pattern.quote(pkg);

        // lockfileVersion 2/3: resolved package entry (top-level or nested node_modules/.../pkg)
        Pattern lockEntry = Pattern.compile(
                "(\"node_modules(?:/[^\"]+)*/" + quotedPkg + "\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\")"
                        + Pattern.quote(oldVersion) + "(\")",
                Pattern.CASE_INSENSITIVE);
        Matcher m = lockEntry.matcher(content);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + newVersion + m.group(2)));
            m.appendTail(sb);
            content = sb.toString();
            // Also bump semver ranges in parent dependency blocks (e.g. handlebars → "minimist": "^1.2.5")
            Optional<String> withRanges = patchNpmJson(content, libName, oldVersion, newVersion);
            return withRanges.isPresent() ? withRanges : Optional.of(content);
        }

        // Legacy lockfile: top-level "minimist": { "version": "..." }
        Pattern blockVersion = Pattern.compile(
                "(\"" + quotedPkg + "\"\\s*:\\s*\\{[^}]*?\"version\"\\s*:\\s*\")"
                        + Pattern.quote(oldVersion) + "(\")",
                Pattern.CASE_INSENSITIVE);
        m = blockVersion.matcher(content);
        if (m.find()) {
            StringBuffer sb = new StringBuffer();
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + newVersion + m.group(2)));
            m.appendTail(sb);
            return Optional.of(sb.toString());
        }

        return patchNpmJson(content, libName, oldVersion, newVersion);
    }

    private static Optional<String> patchMavenPom(String content, String libName, String oldVersion, String newVersion) {
        String artifact = Pattern.quote(artifactId(libName));
        Pattern nearArtifact = Pattern.compile(
                "(<artifactId>\\s*" + artifact + "\\s*</artifactId>\\s*<version>\\s*)"
                        + Pattern.quote(oldVersion) + "(\\s*</version>)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = nearArtifact.matcher(content);
        if (m.find()) {
            return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion) + "$2"));
        }
        Pattern groupArtifact = Pattern.compile(
                "(<groupId>[^<]+</groupId>\\s*<artifactId>\\s*" + artifact + "\\s*</artifactId>\\s*<version>\\s*)"
                        + Pattern.quote(oldVersion) + "(\\s*</version>)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        m = groupArtifact.matcher(content);
        if (m.find()) {
            return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion) + "$2"));
        }
        return Optional.empty();
    }

    private static Optional<String> patchGradleStyle(String content, String libName, String oldVersion, String newVersion) {
        String pkg = Pattern.quote(artifactId(libName));
        Pattern patterns = Pattern.compile(
                "(" + pkg + "[^\\n]*?[:\"']\\s*)" + Pattern.quote(oldVersion),
                Pattern.CASE_INSENSITIVE);
        Matcher m = patterns.matcher(content);
        if (m.find()) {
            return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion)));
        }
        Pattern mavenNotation = Pattern.compile(
                "([\"'][^\"']*:" + pkg + ":)" + Pattern.quote(oldVersion) + "([\"'])",
                Pattern.CASE_INSENSITIVE);
        m = mavenNotation.matcher(content);
        if (m.find()) {
            return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion) + "$2"));
        }
        return patchGeneric(content, libName, oldVersion, newVersion);
    }

    private static Optional<String> patchPythonRequirement(String content, String libName, String oldVersion, String newVersion) {
        String pkg = artifactId(libName).replace("_", "[-_]").replace(".", "\\.");
        Pattern pattern = Pattern.compile(
                "((?:^|[\\s;])(?:" + pkg + ")[^\\n]*?)" + Pattern.quote(oldVersion),
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher m = pattern.matcher(content);
        if (m.find()) {
            return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion)));
        }
        return patchGeneric(content, libName, oldVersion, newVersion);
    }

    private static Optional<String> patchGoMod(String content, String libName, String oldVersion, String newVersion) {
        String module = libName.contains("/") ? libName : null;
        if (module != null) {
            Pattern p = Pattern.compile(
                    "(" + Pattern.quote(module) + "\\s+)" + Pattern.quote(oldVersion),
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(content);
            if (m.find()) {
                return Optional.of(m.replaceFirst("$1" + Matcher.quoteReplacement(newVersion)));
            }
        }
        return patchGeneric(content, libName, oldVersion, newVersion);
    }

    private static Optional<String> patchGeneric(String content, String libName, String oldVersion, String newVersion) {
        String pkg = artifactId(libName);
        boolean mentionsLib = content.contains(pkg)
                || (libName != null && !libName.equals(pkg) && content.contains(libName));
        if (!mentionsLib || !content.contains(oldVersion)) {
            return Optional.empty();
        }
        return Optional.of(content.replace(oldVersion, newVersion));
    }

    private static boolean versionSpecContains(String versionSpec, String oldVersion) {
        return versionSpec != null && oldVersion != null && versionSpec.contains(oldVersion);
    }
}
