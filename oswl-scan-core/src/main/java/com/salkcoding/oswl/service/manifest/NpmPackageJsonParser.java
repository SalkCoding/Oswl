package com.salkcoding.oswl.service.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses declared npm dependencies from package.json (no transitive resolution). */
@Slf4j
public final class NpmPackageJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NpmPackageJsonParser() {
    }

    public static List<ScanPayload.ComponentPayload> parse(Path dir, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(dir.resolve("package.json").toFile());
            addDeps(comps, root.path("dependencies"));
            addDeps(comps, root.path("devDependencies"));
            log.info("[DependencyParser][npm] Parsed {} components from package.json in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][npm] Failed to parse package.json: {}", e.getMessage());
        }
        return comps;
    }

    private static void addDeps(List<ScanPayload.ComponentPayload> comps, JsonNode depsNode) {
        if (depsNode == null || depsNode.isMissingNode()) {
            return;
        }
        depsNode.properties().forEach(entry -> {
            String name = entry.getKey();
            String version = entry.getValue().asText().replaceAll("^[~^>=<]+ *", "");
            comps.add(ManifestComponents.direct(name, version, "NPM"));
        });
    }
}
