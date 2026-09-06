package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@Slf4j
public class CargoManifestParser {

    public List<ScanPayload.ComponentPayload> parseCargoToml(Path dir, String repoName) {
        // Static Cargo.toml — handles inline string form and table form:
        //   foo = "1.0"
        //   foo = { version = "1.0", features = [...] }
        //   foo = { git = "..." }   (no version → skip)
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<String> lines = Files.readAllLines(dir.resolve("Cargo.toml"), StandardCharsets.UTF_8);
            boolean inDeps = false;
            // Inline-string form: foo = "1.2.3-rc1+build"
            Pattern inlineP = Pattern.compile("^([\\w\\-]+)\\s*=\\s*[\"']([\\w.+\\-]+)[\"']\\s*$");
            // Table form: foo = { version = "1.2.3", ... }
            Pattern tableP  = Pattern.compile("^([\\w\\-]+)\\s*=\\s*\\{[^}]*\\bversion\\s*=\\s*[\"']([\\w.+\\-]+)[\"']");
            // Sub-table header: [dependencies.foo]
            Pattern subHeaderP = Pattern.compile("^\\[(?:dev-|build-)?dependencies\\.([\\w\\-]+)]");
            String pendingSubName = null;
            Pattern verLineP = Pattern.compile("^version\\s*=\\s*[\"']([\\w.+\\-]+)[\"']");
            for (String rawLine : lines) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                // Section headers
                if (trimmed.startsWith("[")) {
                    if (trimmed.equals("[dependencies]") || trimmed.equals("[dev-dependencies]")
                            || trimmed.equals("[build-dependencies]")) {
                        inDeps = true;
                        pendingSubName = null;
                        continue;
                    }
                    Matcher sh = subHeaderP.matcher(trimmed);
                    if (sh.find()) {
                        pendingSubName = sh.group(1);
                        inDeps = false;
                        continue;
                    }
                    inDeps = false;
                    pendingSubName = null;
                    continue;
                }
                if (inDeps) {
                    Matcher t = tableP.matcher(trimmed);
                    if (t.find()) {
                        if (seen.add(t.group(1))) comps.add(buildComponent(t.group(1), t.group(2), "CARGO"));
                        continue;
                    }
                    Matcher i = inlineP.matcher(trimmed);
                    if (i.find()) {
                        if (seen.add(i.group(1))) comps.add(buildComponent(i.group(1), i.group(2), "CARGO"));
                    }
                } else if (pendingSubName != null) {
                    Matcher v = verLineP.matcher(trimmed);
                    if (v.find()) {
                        if (seen.add(pendingSubName)) comps.add(buildComponent(pendingSubName, v.group(1), "CARGO"));
                        pendingSubName = null;
                    }
                }
            }
            log.info("[DependencyParser][Cargo] Parsed {} components from Cargo.toml in '{}'", comps.size(), repoName);
        } catch (Exception e) {
            log.error("[DependencyParser][Cargo] Failed to parse Cargo.toml: {}", e.getMessage());
        }
        return comps;
    }

    public List<ScanPayload.ComponentPayload> parseTomlPackageLock(Path lockFile, String ecosystem, String repoName) {
        try {
            String content = Files.readString(lockFile, StandardCharsets.UTF_8);
            String[] blocks = content.split("\\[\\[package\\]\\]");
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            Pattern nameP = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
            Pattern verP  = Pattern.compile("\\bversion\\s*=\\s*\"([^\"]+)\"");
            for (int i = 1; i < blocks.length; i++) {
                Matcher nm = nameP.matcher(blocks[i]);
                Matcher vm = verP.matcher(blocks[i]);
                if (nm.find() && vm.find()) {
                    comps.add(buildComponent(nm.group(1), vm.group(1), ecosystem));
                }
            }
            log.info("[DependencyParser][{}] Parsed {} components from {} in '{}'",
                    ecosystem, comps.size(), lockFile.getFileName(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser] Failed to parse {}: {}", lockFile.getFileName(), e.getMessage());
            return null;
        }
    }

    public static String stripQuotes(String s) { return PythonManifestParser.stripQuotes(s); }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
