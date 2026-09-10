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
public class PythonManifestParser {

    public ParseResult parsePython(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = parseRequirementsFile(dir.resolve("requirements.txt"), repoName);
        return new ParseResult("PYPI", comps != null ? comps : List.of());
    }

    public List<ScanPayload.ComponentPayload> parseRequirementsFile(Path reqFile, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            if (!Files.isRegularFile(reqFile)) {
                return List.of();
            }
            for (String rawLine : Files.readAllLines(reqFile, StandardCharsets.UTF_8)) {
                addPythonRequirementLine(rawLine, seen, comps);
            }
            log.info("[DependencyParser][Python] Parsed {} components from {} in '{}'",
                    comps.size(), reqFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse {}: {}", reqFile, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parsePyprojectToml(Path tomlFile, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(tomlFile, StandardCharsets.UTF_8);
            boolean inProject = false;
            boolean inDepsArray = false;
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inProject = line.equals("[project]");
                    inDepsArray = false;
                    continue;
                }
                if (!inProject) {
                    continue;
                }
                if (line.startsWith("dependencies") && line.contains("[")) {
                    inDepsArray = true;
                    int open = line.indexOf('[');
                    int close = line.indexOf(']');
                    if (close > open) {
                        String inline = line.substring(open + 1, close).trim();
                        if (!inline.isBlank()) {
                            for (String part : inline.split(",")) {
                                addPythonRequirementLine(stripQuotes(part.trim()), seen, comps);
                            }
                        }
                        inDepsArray = !line.endsWith("]");
                    }
                    continue;
                }
                if (inDepsArray) {
                    if (line.equals("]")) {
                        inDepsArray = false;
                        continue;
                    }
                    String dep = line;
                    if (dep.endsWith(",")) {
                        dep = dep.substring(0, dep.length() - 1).trim();
                    }
                    if (dep.equals("]")) {
                        inDepsArray = false;
                        continue;
                    }
                    addPythonRequirementLine(stripQuotes(dep), seen, comps);
                }
            }
            log.info("[DependencyParser][Python] Parsed {} components from {} in '{}'",
                    comps.size(), tomlFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse pyproject.toml {}: {}", tomlFile, e.getMessage());
            return null;
        }
    }

    public void addPythonRequirementLine(
            String rawLine, Set<String> seen, List<ScanPayload.ComponentPayload> comps) {
        String line = rawLine.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("-")) {
            return;
        }
        int hash = line.indexOf('#');
        if (hash >= 0) {
            line = line.substring(0, hash).trim();
        }
        int semi = line.indexOf(';');
        if (semi >= 0) {
            line = line.substring(0, semi).trim();
        }
        if (line.isEmpty()) {
            return;
        }
        Pattern fullForm = Pattern.compile(
                "^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*(===|==|>=|<=|~=|!=|<|>)\\s*([0-9][^\\s,;#]*)");
        Pattern bareName = Pattern.compile("^([A-Za-z0-9][\\w.\\-]*?)(?:\\[[^]]*])?\\s*$");
        String name = null;
        String version = null;
        Matcher m = fullForm.matcher(line);
        if (m.find()) {
            name = m.group(1);
            version = m.group(3);
        } else {
            Matcher b = bareName.matcher(line);
            if (b.matches()) {
                name = b.group(1);
            }
        }
        if (name == null || name.isBlank()) {
            return;
        }
        if (seen.add(name + ":" + (version != null ? version : ""))) {
            comps.add(buildComponent(name, version, "PYPI"));
        }
    }

    public static String stripQuotes(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public List<ScanPayload.ComponentPayload> parsePipfileLock(Path dir, String repoName) {
        try {
            JsonNode root = new ObjectMapper().readTree(dir.resolve("Pipfile.lock").toFile());
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (String section : new String[]{"default", "develop"}) {
                JsonNode deps = root.path(section);
                if (deps.isMissingNode()) continue;
                String scope = "develop".equals(section) ? "dev" : null;
                deps.properties().forEach(e -> {
                    String name = e.getKey();
                    String ver  = e.getValue().path("version").asText("").replaceAll("^==", "");
                    if (!name.isBlank() && !ver.isBlank() && seen.add(name + ":" + ver)) {
                        comps.add(buildComponent(name, ver, "PYPI").withScope(scope));
                    }
                });
            }
            log.info("[DependencyParser][Python] Parsed {} components from Pipfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Python] Failed to parse Pipfile.lock: {}", e.getMessage());
            return null;
        }
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
