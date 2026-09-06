package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

@Slf4j
public class RubyLockParser {

    public List<ScanPayload.ComponentPayload> parseGemfileLock(Path dir, String repoName) {
        try {
            Set<String> seen = new LinkedHashSet<>();
            List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
            boolean inSpecs = false;
            Pattern specLine = Pattern.compile("^ {4}([A-Za-z0-9_.\\-]+)\\s+\\(([^)]+)\\)");
            for (String line : Files.readAllLines(dir.resolve("Gemfile.lock"), StandardCharsets.UTF_8)) {
                if (line.equals("  specs:")) { inSpecs = true; continue; }
                if (inSpecs && !line.startsWith(" ") && !line.isEmpty()) { inSpecs = false; continue; }
                if (!inSpecs) {
                    continue;
                }
                Matcher m = specLine.matcher(line);
                if (!m.find()) {
                    continue;
                }
                String name = m.group(1);
                String version = m.group(2).trim();
                if (version.startsWith(">=") || version.startsWith(">")
                        || version.startsWith("<") || version.startsWith("~>")
                        || version.startsWith("!=") || version.startsWith("=")) {
                    continue;
                }
                if (!version.matches("[0-9].*")) {
                    continue;
                }
                if (seen.add(name + ":" + version)) {
                    comps.add(buildComponent(name, version, "RUBYGEMS"));
                }
            }
            log.info("[DependencyParser][Ruby] Parsed {} components from Gemfile.lock in '{}'", comps.size(), repoName);
            return comps;
        } catch (Exception e) {
            log.warn("[DependencyParser][Ruby] Failed to parse Gemfile.lock: {}", e.getMessage());
            return null;
        }
    }

    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
