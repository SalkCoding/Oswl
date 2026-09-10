package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.yaml.snakeyaml.*;

@Slf4j
public class ComposerLockParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ScanPayload.ComponentPayload> parseComposerLock(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("composer.lock").toFile());
            return parseComposerLockJson(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Composer] Failed to parse composer.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parseComposerLockJson(JsonNode root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addComposerPackages(comps, seen, root.path("packages"), null);
        addComposerPackages(comps, seen, root.path("packages-dev"), "dev");
        log.info("[DependencyParser][Composer] Parsed {} components from composer.lock in '{}'", comps.size(), repoName);
        return comps;
    }

    public void addComposerPackages(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                                     JsonNode packages, String scope) {
        if (packages == null || !packages.isArray()) {
            return;
        }
        for (JsonNode pkg : packages) {
            String name    = pkg.path("name").asText(null);
            String version = normalizeComposerVersion(pkg.path("version").asText(null));
            if (name == null || name.isBlank() || version == null || version.isBlank()) {
                continue;
            }
            if (seen.add(name + ":" + version)) {
                comps.add(buildComponent(name, version, "COMPOSER")
                        .withScope(scope)
                        .withLicenses(extractComposerLicenses(pkg)));
            }
        }
    }

    public static List<String> extractComposerLicenses(JsonNode pkg) {
        JsonNode license = pkg.path("license");
        if (!license.isArray() || license.isEmpty()) {
            return null;
        }
        List<String> licenses = new ArrayList<>();
        for (JsonNode entry : license) {
            String value = entry.asText(null);
            if (value != null && !value.isBlank()) {
                licenses.add(value.strip());
            }
        }
        return licenses.isEmpty() ? null : licenses;
    }

    public static String normalizeComposerVersion(String version) {
        if (version != null && version.length() > 1
                && version.charAt(0) == 'v' && Character.isDigit(version.charAt(1))) {
            return version.substring(1);
        }
        return version;
    }
    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
