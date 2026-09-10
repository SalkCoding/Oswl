package com.salkcoding.oswl.service.iacscan;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.domain.enums.ScanFindingType;
import com.salkcoding.oswl.dto.scan.ScanFindingCandidate;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terraform / Kubernetes manifest / Dockerfile misconfiguration scanner over a Quick Import
 * clone. Line-based heuristic rules — not a full HCL/YAML semantic analysis
 * (that would need a real parser); the goal is to catch the most common, highest-signal
 * misconfigurations without false-negatives-by-omission on the common cases.
 */
@Slf4j
@Service
public class IacScanner {

    private static final long MAX_FILE_BYTES = 1_000_000L;
    private static final int MAX_FINDINGS = 300;

    private static final List<IacRule> RULES = List.of(
            new IacRule("tf-public-s3-acl", "S3 bucket ACL grants public read/write access",
                    RiskLevel.HIGH, IacTargetKind.TERRAFORM,
                    Pattern.compile("acl\\s*=\\s*\"public-read")),
            new IacRule("tf-open-security-group", "Security group ingress open to 0.0.0.0/0",
                    RiskLevel.HIGH, IacTargetKind.TERRAFORM,
                    Pattern.compile("cidr_blocks\\s*=\\s*\\[\\s*\"0\\.0\\.0\\.0/0\"")),
            new IacRule("tf-hardcoded-secret", "Hardcoded credential literal in a Terraform resource",
                    RiskLevel.MEDIUM, IacTargetKind.TERRAFORM,
                    Pattern.compile("(?i)\\b(secret|password|access_key)\\s*=\\s*\"[^\"$]{8,}\"")),

            new IacRule("k8s-privileged-container", "Container runs in privileged mode",
                    RiskLevel.CRITICAL, IacTargetKind.KUBERNETES,
                    Pattern.compile("privileged:\\s*true")),
            new IacRule("k8s-host-network", "Pod uses the host's network namespace",
                    RiskLevel.HIGH, IacTargetKind.KUBERNETES,
                    Pattern.compile("hostNetwork:\\s*true")),
            new IacRule("k8s-run-as-root", "Container explicitly runs as UID 0 (root)",
                    RiskLevel.HIGH, IacTargetKind.KUBERNETES,
                    Pattern.compile("runAsUser:\\s*0\\b")),
            new IacRule("k8s-allow-privilege-escalation", "Container allows privilege escalation",
                    RiskLevel.HIGH, IacTargetKind.KUBERNETES,
                    Pattern.compile("allowPrivilegeEscalation:\\s*true")),

            new IacRule("dockerfile-add-remote-url", "ADD fetches a remote URL without integrity verification",
                    RiskLevel.MEDIUM, IacTargetKind.DOCKERFILE,
                    Pattern.compile("^\\s*ADD\\s+https?://", Pattern.CASE_INSENSITIVE)),
            new IacRule("dockerfile-latest-tag", "Base image pinned to the mutable :latest tag",
                    RiskLevel.LOW, IacTargetKind.DOCKERFILE,
                    Pattern.compile("^\\s*FROM\\s+\\S+:latest\\b", Pattern.CASE_INSENSITIVE))
    );

    private static final Pattern DOCKERFILE_USER = Pattern.compile("^\\s*USER\\s+\\S+", Pattern.CASE_INSENSITIVE);

    public List<ScanFindingCandidate> scan(Path root) {
        List<ScanFindingCandidate> findings = new ArrayList<>();
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
                        log.debug("[IacScan] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[IacScan] walk error under '{}': {}", root, e.getMessage());
        }
        return findings;
    }

    private void scanFile(Path root, Path file, BasicFileAttributes attrs, List<ScanFindingCandidate> findings) throws IOException {
        if (!attrs.isRegularFile() || attrs.size() == 0 || attrs.size() > MAX_FILE_BYTES) return;
        IacTargetKind kind = classify(file);
        if (kind == null) return;

        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!fileReal.startsWith(root)) return;
        String relPath = root.relativize(fileReal).toString().replace('\\', '/');
        List<String> lines = Files.readAllLines(fileReal, StandardCharsets.UTF_8);

        if (kind == IacTargetKind.KUBERNETES && !looksLikeK8sManifest(lines)) return;

        boolean sawUserInstruction = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (IacRule rule : RULES) {
                if (rule.targetKind() != kind) continue;
                Matcher m = rule.pattern().matcher(line);
                if (m.find()) {
                    findings.add(new ScanFindingCandidate(
                            ScanFindingType.IAC, rule.id(), rule.severity(), relPath, i + 1, rule.description(), null));
                    if (findings.size() >= MAX_FINDINGS) return;
                }
            }
            if (kind == IacTargetKind.DOCKERFILE && DOCKERFILE_USER.matcher(line).find()) {
                sawUserInstruction = true;
            }
        }

        if (kind == IacTargetKind.DOCKERFILE && !sawUserInstruction) {
            findings.add(new ScanFindingCandidate(
                    ScanFindingType.IAC, "dockerfile-runs-as-root",
                    RiskLevel.MEDIUM, relPath, 1,
                    "No USER instruction — the container runs as root by default", null));
        }
    }

    private static IacTargetKind classify(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        if (lower.endsWith(".tf")) return IacTargetKind.TERRAFORM;
        if (lower.equals("dockerfile") || lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile")) {
            return IacTargetKind.DOCKERFILE;
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return IacTargetKind.KUBERNETES;
        return null;
    }

    /** Avoids false positives on non-k8s YAML (CI workflows, Helm values, etc.). */
    private static boolean looksLikeK8sManifest(List<String> lines) {
        boolean hasApiVersion = false;
        boolean hasKind = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith("apiVersion:")) hasApiVersion = true;
            if (trimmed.startsWith("kind:")) hasKind = true;
            if (hasApiVersion && hasKind) return true;
        }
        return false;
    }
}
