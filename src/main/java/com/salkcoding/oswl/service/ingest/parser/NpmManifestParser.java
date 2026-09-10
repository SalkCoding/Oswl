package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService.ParseResult;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@Slf4j
public class NpmManifestParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ScanPayload.ComponentPayload> parseNpmLock(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package-lock.json").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();

            if (root.has("packages")) {
                // lockfileVersion 2/3 (npm 7+): "packages" → { "node_modules/x": { version, ... } }
                root.path("packages").properties().forEach(e -> {
                    String pkgPath = e.getKey();
                    if (pkgPath.isEmpty()) return; // skip root entry
                    String name = pkgPath.startsWith("node_modules/")
                            ? pkgPath.substring("node_modules/".length())
                            : pkgPath;
                    String version = e.getValue().path("version").asText(null);
                    if (name.isBlank() || version == null || version.isBlank()) return;
                    if (seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                });
            } else if (root.has("dependencies")) {
                // lockfileVersion 1 (npm 5/6): flat + nested "dependencies" tree
                Deque<Map.Entry<String, JsonNode>> queue = new ArrayDeque<>(root.path("dependencies").properties());
                while (!queue.isEmpty()) {
                    Map.Entry<String, JsonNode> entry = queue.poll();
                    String name    = entry.getKey();
                    JsonNode val   = entry.getValue();
                    String version = val.path("version").asText(null);
                    if (version != null && !version.isBlank() && seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                    if (val.has("dependencies")) {
                        queue.addAll(val.path("dependencies").properties());
                    }
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from package-lock.json in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse package-lock.json for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public ParseResult parseNpmPackageJson(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("package.json").toFile());
            JsonNode deps    = root.path("dependencies");
            JsonNode devDeps = root.path("devDependencies");

            addNpmDeps(comps, deps, null);
            addNpmDeps(comps, devDeps, "dev");
            log.info("[DependencyParser][npm] Parsed {} components from package.json in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][npm] Failed to parse package.json: {}", e.getMessage());
        }
        return new ParseResult("NPM", comps);
    }

    public void addNpmDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode, String scope) {
        if (depsNode == null || depsNode.isMissingNode()) return;
        depsNode.properties().forEach(entry -> {
            String name    = entry.getKey();
            String version = entry.getValue().asText().replaceAll("^[~^>=<]+ *", "");
            comps.add(buildComponent(name, version, "NPM").withScope(scope));
        });
    }

    public List<ScanPayload.ComponentPayload> parseYarnLock(Path dir, String repoName) {
        try {
            List<String> lines = Files.readAllLines(dir.resolve("yarn.lock"), StandardCharsets.UTF_8);
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            List<String> pendingNames = new ArrayList<>();
            for (String raw : lines) {
                if (raw.isEmpty() || raw.startsWith("#")) continue;
                // Header: starts at column 0 and ends with ':'
                if (!Character.isWhitespace(raw.charAt(0)) && raw.endsWith(":")) {
                    pendingNames.clear();
                    String header = raw.substring(0, raw.length() - 1);
                    for (String desc : header.split(",")) {
                        String d = desc.trim();
                        if (d.startsWith("\"") && d.endsWith("\"")) d = d.substring(1, d.length() - 1);
                        // Strip @version: name is everything before the last '@' (but keep leading '@' for scoped)
                        int at = d.lastIndexOf('@');
                        if (at <= 0) continue;
                        pendingNames.add(d.substring(0, at));
                    }
                } else if (!pendingNames.isEmpty() && raw.startsWith("  version")) {
                    Matcher m = Pattern.compile("version[:\\s]+\"?([^\"\\s]+)\"?").matcher(raw);
                    if (m.find()) {
                        String version = m.group(1);
                        for (String name : pendingNames) {
                            if (seen.add(name + ":" + version)) {
                                comps.add(buildComponent(name, version, "NPM"));
                            }
                        }
                    }
                    pendingNames.clear();
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from yarn.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse yarn.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parsePnpmLock(Path dir, String repoName) {
        try {
            List<String> lines = Files.readAllLines(dir.resolve("pnpm-lock.yaml"), StandardCharsets.UTF_8);
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            boolean inSection = false;
            // Modern pnpm v6+: '@scope/pkg@1.2.3':  or  /@scope/pkg@1.2.3:
            // Older pnpm v5  : /@scope/pkg/1.2.3:   or  /pkg/1.2.3:
            Pattern modern = Pattern.compile("^\\s{2}'?/?((?:@[^@/'\\s]+/)?[^@/'\\s]+)@([^()'\\s]+?)(?:\\([^)]+\\))?'?:\\s*$");
            Pattern legacy = Pattern.compile("^\\s{2}/((?:@[^/]+/)?[^/]+)/([0-9][^/_'\\s]+)(?:_[^:'\\s]*)?:\\s*$");
            for (String line : lines) {
                if (line.startsWith("packages:") || line.startsWith("snapshots:")) { inSection = true; continue; }
                if (inSection && !line.isEmpty() && !Character.isWhitespace(line.charAt(0))) { inSection = false; }
                if (!inSection) continue;
                Matcher m = modern.matcher(line);
                if (!m.matches()) m = legacy.matcher(line);
                if (m.matches()) {
                    String name = m.group(1);
                    String version = m.group(2);
                    if (seen.add(name + ":" + version)) {
                        comps.add(buildComponent(name, version, "NPM"));
                    }
                }
            }
            log.info("[DependencyParser][npm] Parsed {} components from pnpm-lock.yaml in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][npm] Failed to parse pnpm-lock.yaml for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
