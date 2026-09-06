package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.ScanFindingCandidate;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CustomRuleScanner {
    private final CustomScanRuleService service;

    public List<ScanFindingCandidate> scan(Path directory) {
        var rules = service.compiled();
        if (rules.isEmpty()) return List.of();
        var findings = new ArrayList<ScanFindingCandidate>();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        long[] bytes = {0}; int[] files = {0}; boolean[] incomplete = {false};
        try {
            Path root = directory.toRealPath();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private boolean exhausted() { return System.nanoTime() >= deadline || findings.size() >= 300 || bytes[0] > 16_000_000 || files[0] > 2000 || Thread.currentThread().isInterrupted(); }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (exhausted()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                    return !dir.equals(root) && ManifestCollectRules.SKIP_DIRS.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (exhausted()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                    files[0]++;
                    var applicable = rules.stream().filter(r -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(r.rule().fileSuffix().toLowerCase(Locale.ROOT))).toList();
                    if (applicable.isEmpty()) return FileVisitResult.CONTINUE;
                    if (attrs.size() > 1_000_000) { incomplete[0] = true; return FileVisitResult.CONTINUE; }
                    byte[] content;
                    try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { content = in.readNBytes(1_000_001); }
                    bytes[0] += content.length;
                    if (content.length > 1_000_000 || exhausted()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                    String text = new String(content, StandardCharsets.UTF_8);
                    if (text.indexOf('\0') >= 0) { incomplete[0] = true; return FileVisitResult.CONTINUE; }
                    String[] lines = text.split("\\R", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].length() > 8192) { incomplete[0] = true; continue; }
                        for (var compiled : applicable) {
                            if (exhausted()) { incomplete[0] = true; return FileVisitResult.TERMINATE; }
                            if (compiled.pattern().matcher(lines[i]).find()) {
                                var rule = compiled.rule();
                                findings.add(new ScanFindingCandidate(rule.type(), "custom-" + rule.id() + "@" + compiled.revision(), rule.severity(),
                                        root.relativize(file).toString().replace('\\','/'), i + 1, rule.description(), null));
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException e) { incomplete[0] = true; return FileVisitResult.CONTINUE; }
            });
        } catch (IOException e) { incomplete[0] = true; }
        if (incomplete[0]) findings.add(new ScanFindingCandidate(ScanFindingType.IAC, "custom-scan-incomplete@" + rules.getFirst().revision(), RiskLevel.HIGH,
                ".", null, "Custom rule scan incomplete: an input, time or finding limit was reached, or a file could not be read", null));
        return List.copyOf(findings);
    }
}
