package com.salkcoding.oswl.service.secretscan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import com.salkcoding.oswl.dto.scan.ScanFindingCandidate;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex + entropy secret scanner over a Quick Import clone.
 *
 * Never persists the matched value — callers only ever see the rule id, description and a
 * SHA-256 fingerprint of the match, derived here and never reversible back to the secret.
 */
@Slf4j
@Service
public class SecretScanner {

    private static final String RULES_RESOURCE = "secretscan/secret-rules.json";
    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final int MAX_FINDINGS = 300;
    private static final double ENTROPY_THRESHOLD = 4.0;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".ico", ".bmp", ".webp", ".pdf",
            ".zip", ".tar", ".gz", ".7z", ".rar", ".jar", ".war", ".class",
            ".so", ".dll", ".dylib", ".exe", ".bin", ".woff", ".woff2", ".ttf",
            ".eot", ".mp3", ".mp4", ".avi", ".mov", ".db", ".sqlite");

    private static final Pattern ENTROPY_CANDIDATE = Pattern.compile(
            "(?i)\\b(?:key|token|secret|api[_-]?key)\\s*[:=]\\s*['\"]([A-Za-z0-9+/_=-]{20,100})['\"]");

    private List<SecretRule> rules = List.of();

    @PostConstruct
    void loadRules() {
        try (var in = new ClassPathResource(RULES_RESOURCE).getInputStream()) {
            ObjectMapper mapper = new ObjectMapper();
            SecretRule.RawRule[] raw = mapper.readValue(in, SecretRule.RawRule[].class);
            List<SecretRule> compiled = new ArrayList<>();
            for (SecretRule.RawRule r : raw) {
                compiled.add(r.compile());
            }
            this.rules = List.copyOf(compiled);
            log.info("[SecretScan] Loaded {} secret rules", rules.size());
        } catch (Exception e) {
            log.warn("[SecretScan] Failed to load {}: {} — secret scanning disabled", RULES_RESOURCE, e.getMessage());
            this.rules = List.of();
        }
    }

    /** Scans every text file under {@code root} (already a verified real path) for secrets. */
    public List<ScanFindingCandidate> scan(Path root) {
        List<ScanFindingCandidate> findings = new ArrayList<>();
        if (rules.isEmpty()) return findings;
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                    if (findings.size() >= MAX_FINDINGS) return FileVisitResult.TERMINATE;
                    if (!dir.equals(root) && ManifestCollectRules.SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    if (findings.size() >= MAX_FINDINGS) return FileVisitResult.TERMINATE;
                    try {
                        scanFile(root, file, attrs, findings);
                    } catch (Exception e) {
                        log.debug("[SecretScan] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[SecretScan] walk error under '{}': {}", root, e.getMessage());
        }
        return findings;
    }

    private void scanFile(Path root, Path file, BasicFileAttributes attrs, List<ScanFindingCandidate> findings) throws IOException {
        if (!attrs.isRegularFile() || attrs.size() == 0 || attrs.size() > MAX_FILE_BYTES) return;
        String name = file.getFileName().toString().toLowerCase();
        for (String ext : BINARY_EXTENSIONS) {
            if (name.endsWith(ext)) return;
        }
        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!fileReal.startsWith(root)) return;
        String relPath = root.relativize(fileReal).toString().replace('\\', '/');

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(fileReal), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.indexOf('\0') >= 0) return; // binary content slipped past the extension check
                Set<String> matchedRuleIds = new HashSet<>();
                for (SecretRule rule : rules) {
                    Matcher m = rule.pattern().matcher(line);
                    if (m.find()) {
                        matchedRuleIds.add(rule.id());
                        findings.add(new ScanFindingCandidate(
                                ScanFindingType.SECRET, rule.id(), rule.severity(),
                                relPath, lineNo, rule.description(), fingerprint(m.group())));
                        if (findings.size() >= MAX_FINDINGS) return;
                    }
                }
                checkEntropy(line, relPath, lineNo, matchedRuleIds, findings);
                if (findings.size() >= MAX_FINDINGS) return;
            }
        }
    }

    private void checkEntropy(String line, String relPath, int lineNo, Set<String> alreadyMatched,
                               List<ScanFindingCandidate> findings) {
        Matcher m = ENTROPY_CANDIDATE.matcher(line);
        if (!m.find()) return;
        String value = m.group(1);
        if (isLikelyPlaceholder(value)) return;
        double entropy = shannonEntropy(value);
        if (entropy < ENTROPY_THRESHOLD) return;
        findings.add(new ScanFindingCandidate(
                ScanFindingType.SECRET, "generic-high-entropy-string", RiskLevel.MEDIUM,
                relPath, lineNo, "High-entropy string assigned to a key/token/secret-like field", fingerprint(value)));
    }

    private static boolean isLikelyPlaceholder(String value) {
        String lower = value.toLowerCase();
        return lower.contains("example") || lower.contains("changeme") || lower.contains("placeholder")
                || lower.contains("dummy") || lower.contains("xxxx") || lower.contains("${") || lower.contains("{{")
                || lower.chars().distinct().count() <= 2;
    }

    private static double shannonEntropy(String s) {
        int[] counts = new int[256];
        for (int i = 0; i < s.length(); i++) {
            counts[s.charAt(i) & 0xFF]++;
        }
        double entropy = 0.0;
        int len = s.length();
        for (int count : counts) {
            if (count == 0) continue;
            double p = (double) count / len;
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }

    /** First 12 hex chars of SHA-256(value) — never reversible back to the value. */
    static String fingerprint(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
