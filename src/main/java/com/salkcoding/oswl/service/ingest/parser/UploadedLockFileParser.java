package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import com.salkcoding.oswl.service.ingest.CondaPypiMappingService;
import lombok.RequiredArgsConstructor;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
@Slf4j
@RequiredArgsConstructor
public class UploadedLockFileParser {
    private final CondaPypiMappingService condaPypiMappingService;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public List<ScanPayload.ComponentPayload> parseUploadedLockFile(byte[] content, String label) {
        try {
            String text = new String(content, StandardCharsets.UTF_8);
            String head = text.substring(0, Math.min(text.length(), 200)).stripLeading();
            if (head.startsWith("{")) {
                JsonNode root = OBJECT_MAPPER.readTree(content);
                if (isComposerLockJson(root)) {
                    return new ComposerLockParser().parseComposerLockJson(root, label);
                }
                if (isConanLockJson(root)) {
                    return new ConanLockParser().parseConanLockJson(root, label);
                }
                return null;
            }
            // XML — the caller's CycloneDX path handles it.
            if (head.startsWith("<")) {
                return null;
            }
            // Podfile.lock is YAML-shaped but parsed line-wise, same as the repo-walk parser.
            if (head.startsWith("PODS:")) {
                return new CocoaPodsLockParser().parsePodfileLockLines(text.lines().toList(), label);
            }
            // `conda list --explicit` spec file: comment headers, an @EXPLICIT marker, then
            // one package URL per line. It has no stable filename, so content shape is the
            // only way it can arrive here.
            List<String> lines = text.lines().toList();
            if (lines.stream().anyMatch(l -> l.strip().equals("@EXPLICIT"))) {
                return new CondaLockParser(condaPypiMappingService).parseCondaExplicitLines(lines, label);
            }
            // YAML lock files: pixi.lock (environments → per-platform package URLs) or
            // conda-lock.yml (flat "package" list).
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            if (parsed instanceof Map<?, ?> root) {
                if (root.containsKey("environments")) {
                    return new CondaLockParser(condaPypiMappingService).parsePixiLockYaml(root, label);
                }
                if (root.get("package") instanceof List<?>) {
                    return new CondaLockParser(condaPypiMappingService).parseCondaLockYaml(root, label);
                }
            }
        } catch (Exception e) {
            log.debug("[DependencyParser] Uploaded content is not a supported lock file: {}", e.getMessage());
        }
        return null;
    }

    public static boolean isComposerLockJson(JsonNode root) {
        return root.path("packages").isArray()
                && (root.has("content-hash") || root.has("_readme") || root.has("packages-dev"));
    }

    public static boolean isConanLockJson(JsonNode root) {
        return root.has("graph_lock")
                || (root.path("requires").isArray() && root.has("version"));
    }
}
