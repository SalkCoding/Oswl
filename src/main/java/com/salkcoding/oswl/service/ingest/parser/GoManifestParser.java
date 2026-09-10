package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@Slf4j
public class GoManifestParser {

    public List<ScanPayload.ComponentPayload> parseGoModDeclared(Path dir, String repoName) {
        // go.mod fallback — we trim each line first, so both block and single patterns
        // match against the trimmed text.
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("go.mod"), StandardCharsets.UTF_8);
            boolean inRequire = false;
            // "require module v1.2.3" (single) and inside a require( … ) block "module v1.2.3"
            Pattern single = Pattern.compile("^require\\s+(\\S+)\\s+(v\\S+)");
            Pattern entry  = Pattern.compile("^(\\S+)\\s+(v\\S+)");
            for (String rawLine : lines) {
                // Strip end-of-line comments (e.g. "// indirect") before parsing.
                String t = rawLine;
                int idx = t.indexOf("//");
                if (idx >= 0) t = t.substring(0, idx);
                t = t.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("require (")) { inRequire = true; continue; }
                if (inRequire && t.equals(")"))                         { inRequire = false; continue; }
                Matcher m = inRequire ? entry.matcher(t) : single.matcher(t);
                if (m.find() && seen.add(m.group(1))) {
                    comps.add(buildComponent(m.group(1), m.group(2), "GO"));
                }
            }
            log.info("[DependencyParser][Go] Parsed {} components from go.mod in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][Go] Failed to parse go.mod: {}", e.getMessage());
        }
        return comps;
    }

    public List<ScanPayload.ComponentPayload> parseGoSum(Path dir, String repoName) {
        try {
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Pattern p = Pattern.compile("^(\\S+)\\s+(v[^\\s/]+)(?:/go\\.mod)?\\s");
            for (String line : Files.readAllLines(dir.resolve("go.sum"), StandardCharsets.UTF_8)) {
                Matcher m = p.matcher(line);
                if (m.find() && seen.add(m.group(1))) {
                    comps.add(buildComponent(m.group(1), m.group(2), "GO"));
                }
            }
            log.info("[DependencyParser][Go] Parsed {} components from go.sum in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Go] Failed to parse go.sum: {}", e.getMessage());
            return null;
        }
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
