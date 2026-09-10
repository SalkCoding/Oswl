package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.yaml.snakeyaml.*;

@Slf4j
public class CocoaPodsLockParser {
    private static final Pattern PODFILE_TOP_LEVEL_POD =
            Pattern.compile("^ {2}- ([A-Za-z0-9_.+-]+)(?:/[A-Za-z0-9_.+-]+)?\\s+\\(([^)]+)\\):?\\s*$");

    public List<ScanPayload.ComponentPayload> parsePodfileLock(Path dir, String repoName) {
        try {
            List<String> lines = Files.readAllLines(dir.resolve("Podfile.lock"), StandardCharsets.UTF_8);
            return parsePodfileLockLines(lines, repoName);
        } catch (Exception e) {
            log.warn("[DependencyParser][CocoaPods] Failed to parse Podfile.lock for '{}': {}", repoName, e.getMessage());
            return null;
        }
    }

    public List<ScanPayload.ComponentPayload> parsePodfileLockLines(List<String> lines, String repoName) {
        List<ScanPayload.ComponentPayload> comps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        boolean inPods = false;
        for (String line : lines) {
            if (!inPods) {
                if (line.strip().equals("PODS:")) inPods = true;
                continue;
            }
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                break; // reached the next top-level section (DEPENDENCIES:, SPEC REPOS:, ...)
            }
            Matcher m = PODFILE_TOP_LEVEL_POD.matcher(line);
            if (!m.matches()) continue;
            String name = m.group(1);
            String version = m.group(2).trim();
            // A pod pinned to a git ref/branch/podspec path has no released version here
            // (e.g. "MyPod (from `https://github.com/...`, branch `main`)") — skip it rather
            // than feed a non-existent version into the Specs trunk lookup.
            if (!version.matches("[0-9][0-9A-Za-z.+-]*")) continue;
            if (seen.add(name + ":" + version)) {
                comps.add(buildComponent(name, version, "COCOAPODS"));
            }
        }
        log.info("[DependencyParser][CocoaPods] Parsed {} components from Podfile.lock in '{}'", comps.size(), repoName);
        return comps;
    }
    private ScanPayload.ComponentPayload buildComponent(String name, String version, String ecosystem) {
        return ScanPayload.ComponentPayload.create(name, version, ecosystem, "Direct", List.of());
    }
}
