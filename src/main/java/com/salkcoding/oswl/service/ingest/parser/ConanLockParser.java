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
public class ConanLockParser {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ScanPayload.ComponentPayload> parseConanLock(Path dir, String repoName) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(dir.resolve("conan.lock").toFile());
            return parseConanLockJson(root, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][Conan] Failed to parse conan.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parseConanLockJson(JsonNode root, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (root.has("graph_lock")) {
            root.path("graph_lock").path("nodes").properties().forEach(e ->
                    addConanRef(comps, seen, e.getValue().path("ref").asText(null), null));
        } else {
            addConanRefs(comps, seen, root.path("requires"), null);
            addConanRefs(comps, seen, root.path("build_requires"), "dev");
        }
        log.info("[DependencyParser][Conan] Parsed {} components from conan.lock in '{}'", comps.size(), repoName);
        return comps;
    }

    public void addConanRefs(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                              JsonNode refs, String scope) {
        if (refs == null || !refs.isArray()) {
            return;
        }
        for (JsonNode ref : refs) {
            addConanRef(comps, seen, ref.asText(null), scope);
        }
    }

    public void addConanRef(List<ScanPayload.ComponentPayload> comps, Set<String> seen,
                             String ref, String scope) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        String r = ref.trim();
        int hash = r.indexOf('#');
        if (hash >= 0) {
            r = r.substring(0, hash); // strip #revision%timestamp
        }
        int at = r.indexOf('@');
        if (at >= 0) {
            r = r.substring(0, at); // strip @user/channel
        }
        int slash = r.indexOf('/');
        if (slash <= 0 || slash == r.length() - 1) {
            return;
        }
        String name    = r.substring(0, slash).trim();
        String version = r.substring(slash + 1).trim();
        if (name.isBlank() || version.isBlank()) {
            return;
        }
        if (seen.add(name + ":" + version)) {
            comps.add(buildComponent(name, version, "CONAN").withScope(scope));
        }
    }
    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
