package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.CondaPypiMappingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Slf4j
@RequiredArgsConstructor
public class CondaLockParser {
    private static final Yaml SAFE_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));
    private final CondaPypiMappingService condaPypiMappingService;

    public List<ScanPayload.ComponentPayload> parseCondaLock(Path dir, String repoName) {
        try (var reader = Files.newBufferedReader(dir.resolve("conda-lock.yml"), StandardCharsets.UTF_8)) {
            Object parsed = SAFE_YAML.load(reader);
            if (!(parsed instanceof Map<?, ?> root)) {
                return null;
            }
            return parseCondaLockYaml(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Conda] Failed to parse conda-lock.yml for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parseCondaLockYaml(Map<?, ?> root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Object packagesObj = root.get("package");
        if (packagesObj instanceof List<?> packages) {
            for (Object pkgObj : packages) {
                if (!(pkgObj instanceof Map<?, ?> pkg)) continue;
                String name = yamlString(pkg.get("name"));
                String version = yamlString(pkg.get("version"));
                String manager = yamlString(pkg.get("manager"));
                if (name == null || name.isBlank() || version == null || version.isBlank()) continue;
                if (!seen.add(name.toLowerCase(Locale.ROOT) + ":" + version)) continue;

                if ("pip".equalsIgnoreCase(manager)) {
                    comps.add(buildComponent(name, version, "PYPI"));
                    continue;
                }
                String pypiName = condaPypiMappingService.resolvePypiName(name);
                comps.add(pypiName != null
                        ? buildComponent(pypiName, version, "PYPI")
                        : buildComponent(name, version, "CONDA"));
            }
        }
        log.info("[DependencyParser][Conda] Parsed {} components from conda-lock.yml in '{}'", comps.size(), repoName);
        return comps;
    }

    public static String yamlString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public List<ScanPayload.ComponentPayload> parsePixiLock(Path dir, String repoName) {
        try (var reader = Files.newBufferedReader(dir.resolve("pixi.lock"), StandardCharsets.UTF_8)) {
            Object parsed = SAFE_YAML.load(reader);
            if (!(parsed instanceof Map<?, ?> root)) {
                return null;
            }
            return parsePixiLockYaml(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Conda] Failed to parse pixi.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parsePixiLockYaml(Map<?, ?> root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (root.get("environments") instanceof Map<?, ?> environments) {
            for (Object envObj : environments.values()) {
                if (!(envObj instanceof Map<?, ?> env)) continue;
                if (!(env.get("packages") instanceof Map<?, ?> byPlatform)) continue;
                for (Object listObj : byPlatform.values()) {
                    if (!(listObj instanceof List<?> entries)) continue;
                    for (Object entryObj : entries) {
                        if (!(entryObj instanceof Map<?, ?> entry)) continue;
                        String name = null;
                        String version = null;
                        boolean pypi = entry.get("pypi") != null;
                        if (pypi) {
                            name = yamlString(entry.get("name"));
                            version = yamlString(entry.get("version"));
                        } else if (entry.get("conda") != null) {
                            String[] nv = parseCondaPackageNameVersion(yamlString(entry.get("conda")));
                            if (nv != null) {
                                name = nv[0];
                                version = nv[1];
                            }
                        }
                        if (name == null || name.isBlank() || version == null || version.isBlank()) continue;
                        if (!seen.add(name.toLowerCase(Locale.ROOT) + ":" + version)) continue;
                        if (pypi) {
                            comps.add(buildComponent(name, version, "PYPI"));
                            continue;
                        }
                        String pypiName = condaPypiMappingService.resolvePypiName(name);
                        comps.add(pypiName != null
                                ? buildComponent(pypiName, version, "PYPI")
                                : buildComponent(name, version, "CONDA"));
                    }
                }
            }
        }
        log.info("[DependencyParser][Conda] Parsed {} components from pixi.lock in '{}'", comps.size(), repoName);
        return comps;
    }

    public List<ScanPayload.ComponentPayload> parseCondaExplicitLines(List<String> lines, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String line : lines) {
            String url = line.strip();
            if (url.isEmpty() || url.startsWith("#") || url.startsWith("@")) continue;
            String[] nv = parseCondaPackageNameVersion(url);
            if (nv == null) continue;
            if (!seen.add(nv[0].toLowerCase(Locale.ROOT) + ":" + nv[1])) continue;
            String pypiName = condaPypiMappingService.resolvePypiName(nv[0]);
            comps.add(pypiName != null
                    ? buildComponent(pypiName, nv[1], "PYPI")
                    : buildComponent(nv[0], nv[1], "CONDA"));
        }
        log.info("[DependencyParser][Conda] Parsed {} components from explicit spec in '{}'", comps.size(), repoName);
        return comps;
    }

    public static String[] parseCondaPackageNameVersion(String urlOrFilename) {
        if (urlOrFilename == null) return null;
        String filename = urlOrFilename;
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) filename = filename.substring(slash + 1);
        if (filename.endsWith(".conda")) {
            filename = filename.substring(0, filename.length() - ".conda".length());
        } else if (filename.endsWith(".tar.bz2")) {
            filename = filename.substring(0, filename.length() - ".tar.bz2".length());
        } else {
            return null;
        }
        int lastDash = filename.lastIndexOf('-');
        if (lastDash <= 0) return null;
        int prevDash = filename.lastIndexOf('-', lastDash - 1);
        if (prevDash <= 0) return null;
        String name = filename.substring(0, prevDash);
        String version = filename.substring(prevDash + 1, lastDash);
        if (name.isEmpty() || version.isEmpty() || !Character.isDigit(version.charAt(0))) return null;
        return new String[]{name, version};
    }
    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
