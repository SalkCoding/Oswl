package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.w3c.dom.*;

@Slf4j
public class NativeManifestParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ScanPayload.ComponentPayload> parseVcpkgJson(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("vcpkg.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            JsonNode deps = root.path("dependencies");
            if (deps.isArray()) {
                for (JsonNode dep : deps) {
                    String name = null;
                    String version = null;
                    if (dep.isTextual()) {
                        name = dep.asText(null);
                    } else if (dep.isObject()) {
                        name = dep.path("name").asText(null);
                        if (dep.has("version>=")) {
                            version = dep.path("version>=").asText(null);
                        } else if (dep.has("version")) {
                            version = dep.path("version").asText(null);
                        } else if (dep.has("baseline")) {
                            version = dep.path("baseline").asText(null);
                        }
                    }
                    if (name == null || name.isBlank()) continue;
                    name = name.trim();
                    version = (version != null && !version.isBlank()) ? version.trim() : null;
                    if (seen.add(name + ":" + (version != null ? version : ""))) {
                        comps.add(buildComponent(name, version, "VCPKG"));
                    }
                }
            }
            log.info("[DependencyParser][vcpkg] Parsed {} components from vcpkg.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][vcpkg] Failed to parse vcpkg.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parseVcpkgConfigurationJson(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("vcpkg-configuration.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            extractVcpkgRegistryPackages(root, seen, comps);
            log.info("[DependencyParser][vcpkg] Parsed {} components from vcpkg-configuration.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][vcpkg] Failed to parse vcpkg-configuration.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public void extractVcpkgRegistryPackages(JsonNode node, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        if (node == null || node.isMissingNode()) return;
        if (node.isArray()) {
            for (JsonNode item : node) {
                extractVcpkgRegistryPackage(item, seen, comps);
                extractVcpkgRegistryPackages(item, seen, comps);
            }
        } else if (node.isObject()) {
            extractVcpkgRegistryPackage(node, seen, comps);
            node.fields().forEachRemaining(e -> extractVcpkgRegistryPackages(e.getValue(), seen, comps));
        }
    }

    public void extractVcpkgRegistryPackage(JsonNode obj, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        if (!obj.isObject()) return;
        String name = obj.path("name").asText(null);
        if (name == null || name.isBlank()) return;
        String version = null;
        for (String key : new String[]{"version", "baseline", "version>="}) {
            if (obj.has(key)) {
                version = obj.path(key).asText(null);
                if (version != null && !version.isBlank()) break;
            }
        }
        String v = (version != null && !version.isBlank()) ? version.trim() : null;
        if (seen.add(name + ":" + (v != null ? v : ""))) {
            comps.add(buildComponent(name, v, "VCPKG"));
        }
    }

    public List<ScanPayload.ComponentPayload> parseCMakeLists(Path dir, String repoName) {
        Path cmake = dir.resolve("CMakeLists.txt");
        if (!Files.isRegularFile(cmake)) return List.of();
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            String content = Files.readString(cmake, StandardCharsets.UTF_8);
            // Normalize line continuations and remove CMake comments
            content = content.replaceAll("\\\\\\r?\\n", " ");
            content = content.replaceAll("#[^\\n]*", " ");
            parseCMakeCommand(content, "FetchContent_Declare", seen, comps);
            parseCMakeCommand(content, "ExternalProject_Add", seen, comps);
            log.info("[DependencyParser][CMake] Parsed {} components from CMakeLists.txt in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][CMake] Failed to parse CMakeLists.txt for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public void parseCMakeCommand(String content, String command, Set<String> seen,
                                   List<ScanPayload.ComponentPayload> comps) {
        // Match command(ident ... ) allowing nested parentheses one level deep.
        Pattern cmdP = Pattern.compile(
                "\\b" + Pattern.quote(command) + "\\s*\\(\\s*([A-Za-z0-9_\\-]+)\\s+((?:[^()]|\\([^()]*\\))*)\\)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = cmdP.matcher(content);
        while (m.find()) {
            String ident = m.group(1);
            String body = m.group(2);
            String gitRepo = extractCMakeQuotedArg(body, "GIT_REPOSITORY");
            String gitTag = extractCMakeQuotedArg(body, "GIT_TAG");
            String url = extractCMakeQuotedArg(body, "URL");
            String name = cmakeBasenameFromUrl(gitRepo != null ? gitRepo : url);
            if (name == null) name = ident;
            String version = null;
            if (gitTag != null && !gitTag.isBlank()) {
                version = normalizeGitTag(gitTag);
            } else if (url != null && !url.isBlank()) {
                version = versionFromArchiveUrl(url);
            }
            if (version == null || version.isBlank()) continue;
            if (seen.add(name + ":" + version)) {
                comps.add(buildComponent(name, version, "VENDORED"));
            }
        }
    }

    public String extractCMakeQuotedArg(String body, String key) {
        Pattern p = Pattern.compile(
                "\\b" + Pattern.quote(key) + "\\s+\"([^\"]*)\"",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    public String cmakeBasenameFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String trimmed = url.replaceAll("\\.git$", "");
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) return trimmed;
        String base = trimmed.substring(slash + 1);
        return base.isBlank() ? null : base;
    }

    public String normalizeGitTag(String tag) {
        if (tag == null) return null;
        String t = tag.trim();
        if (t.startsWith("v") && t.length() > 1 && Character.isDigit(t.charAt(1))) {
            return t.substring(1);
        }
        return t;
    }

    public String versionFromArchiveUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String base = cmakeBasenameFromUrl(url);
        if (base == null) return null;
        // Strip common archive suffixes and look for a version tail: name-1.2.3.tar.gz
        String withoutExt = base.replaceAll("\\.(tar\\.gz|tar\\.bz2|tar\\.xz|zip|tgz|tbz2|txz)$", "");
        int dash = withoutExt.lastIndexOf('-');
        if (dash > 0 && dash < withoutExt.length() - 1) {
            String candidate = withoutExt.substring(dash + 1);
            if (candidate.matches("[0-9].*")) return candidate;
        }
        return null;
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
