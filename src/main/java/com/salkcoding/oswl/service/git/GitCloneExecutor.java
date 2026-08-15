package com.salkcoding.oswl.service.git;

import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs {@code git clone} with a clean HTTPS URL and credentials supplied via {@code GIT_ASKPASS}
 * (not embedded in the URL / argv).
 *
 * <p>On the default path ({@code oswl.quick-import.allow-build-exec=false}) the clone is blobless
 * ({@code --filter=blob:none}) and sparse-checked-out to manifest files only
 * ({@link ManifestCollectRules#sparseCheckoutPatterns()}), since static parsing never reads other
 * sources. When build-tool execution is allowed, mvnw/gradlew need the full tree, so a plain
 * shallow clone is used. Servers without partial-clone support fall back to the plain clone once.
 */
@Slf4j
@Component
public class GitCloneExecutor {

    private static final String ENV_USERNAME = "OSWL_GIT_USERNAME";
    private static final String ENV_PASSWORD = "OSWL_GIT_PASSWORD";

    private static final long GIT_TIMEOUT_MINUTES = 5;

    /** git writes human-readable output in the OS console codepage (e.g. MS949 on Korean Windows). */
    private static final Charset CONSOLE_CHARSET = Charset.forName(System.getProperty("native.encoding", "UTF-8"));

    @Value("${oswl.clone.sparse-enabled:true}")
    private boolean sparseEnabled;

    @Value("${oswl.quick-import.allow-build-exec:false}")
    private boolean allowBuildExec;

    /**
     * @param credentials {@code null} for anonymous HTTPS clone (public repositories).
     */
    public void clone(String repositoryUrl, GitCloneCredentials credentials, String branch,
                      Path targetDir, String jobId) throws Exception {
        Path askpass = credentials != null ? writeAskpassScript() : null;
        try {
            if (sparseEnabled && !allowBuildExec) {
                try {
                    cloneSparse(repositoryUrl, branch, targetDir, jobId, askpass, credentials);
                    return;
                } catch (Exception e) {
                    log.info("[QuickImport][{}] Blobless/sparse clone failed ({}); falling back to full shallow clone",
                            jobId, e.getMessage());
                    emptyDirectory(targetDir);
                }
            }
            cloneFull(repositoryUrl, branch, targetDir, jobId, askpass, credentials);
        } finally {
            if (askpass != null) {
                try {
                    Files.deleteIfExists(askpass);
                } catch (IOException e) {
                    log.debug("[QuickImport][{}] Could not delete GIT_ASKPASS script: {}", jobId, e.getMessage());
                }
            }
        }
    }

    /** Plain shallow clone — the original behavior, kept for build-exec mode and as fallback. */
    private void cloneFull(String repositoryUrl, String branch, Path targetDir, String jobId,
                           Path askpass, GitCloneCredentials credentials) throws Exception {
        List<String> cmd = gitBase();
        cmd.addAll(List.of("clone", "--depth", "1", "--single-branch", "--quiet"));
        addBranch(cmd, branch);
        cmd.add(repositoryUrl);
        cmd.add(targetDir.toString());
        log.info("[QuickImport][{}] Running: git clone --depth 1 {} ({})",
                jobId, repositoryUrl, credentials != null ? "credentials via GIT_ASKPASS" : "anonymous");
        runGit(cmd, askpass, credentials, jobId, "git clone");
    }

    /**
     * Blobless sparse clone: only the trees are transferred up front; blobs are fetched on demand
     * for the manifest patterns checked out by {@code sparse-checkout set --no-cone}.
     */
    private void cloneSparse(String repositoryUrl, String branch, Path targetDir, String jobId,
                             Path askpass, GitCloneCredentials credentials) throws Exception {
        List<String> cmd = gitBase();
        cmd.addAll(List.of("clone", "--depth", "1", "--single-branch", "--no-tags",
                "--filter=blob:none", "--sparse", "--quiet"));
        addBranch(cmd, branch);
        cmd.add(repositoryUrl);
        cmd.add(targetDir.toString());
        log.info("[QuickImport][{}] Running: git clone --depth 1 --filter=blob:none --sparse {} ({})",
                jobId, repositoryUrl, credentials != null ? "credentials via GIT_ASKPASS" : "anonymous");
        runGit(cmd, askpass, credentials, jobId, "git clone");

        // sparse-checkout lazy-fetches blobs from the promisor remote; for private repos that
        // fetch authenticates like any other remote access, so pass GIT_ASKPASS through (unused
        // when every blob is already local).
        List<String> sparse = gitBase();
        sparse.addAll(List.of("-C", targetDir.toString(), "sparse-checkout", "set", "--no-cone"));
        sparse.addAll(ManifestCollectRules.sparseCheckoutPatterns());
        runGit(sparse, askpass, credentials, jobId, "git sparse-checkout");
    }

    private static List<String> gitBase() {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            cmd.add("-c");
            cmd.add("core.longpaths=true");
        }
        return cmd;
    }

    private static void addBranch(List<String> cmd, String branch) {
        if (branch != null && !branch.isBlank()) {
            cmd.addAll(List.of("--branch", branch));
        }
    }

    private static void runGit(List<String> cmd, Path askpass, GitCloneCredentials credentials,
                               String jobId, String description) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        if (credentials != null && askpass != null) {
            pb.environment().put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
            pb.environment().put(ENV_USERNAME, credentials.username());
            pb.environment().put(ENV_PASSWORD, credentials.password());
        }

        Process proc = pb.start();
        final String[] outputHolder = {""};
        Thread outputReader = Thread.ofVirtual().start(() -> {
            try {
                outputHolder[0] = new String(proc.getInputStream().readAllBytes(), CONSOLE_CHARSET);
            } catch (IOException ignored) {
            }
        });
        boolean finished = proc.waitFor(GIT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            proc.destroyForcibly();
            throw new RuntimeException(description + " timed out after " + GIT_TIMEOUT_MINUTES + " minutes");
        }
        try {
            outputReader.join(2_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            String safe = redactSecrets(outputHolder[0]);
            throw new RuntimeException(description + " failed (exit " + exitCode + "): " + safe.trim());
        }
        log.debug("[QuickImport][{}] {} finished: {}", jobId, description, redactSecrets(outputHolder[0]).trim());
    }

    /** Removes the contents of a partially cloned directory so a fallback clone can reuse it. */
    private static void emptyDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                try (Stream<Path> walk = Files.walk(child)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
    }

    private static String redactSecrets(String output) {
        if (output == null) {
            return "";
        }
        return output
                .replaceAll("(https?://)([^@\\s]+@)", "$1***@")
                .replaceAll("(?i)(password|token|auth)[=:]\\s*\\S+", "$1=***");
    }

    private static Path writeAskpassScript() throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path script = Files.createTempFile("oswl-git-askpass-", windows ? ".cmd" : ".sh");
        try {
            if (windows) {
                Files.writeString(script, """
                        @echo off
                        echo %1 | findstr /I "Username" >nul && (echo %OSWL_GIT_USERNAME%) || (echo %OSWL_GIT_PASSWORD%)
                        """, StandardCharsets.UTF_8);
            } else {
                Files.writeString(script, """
                        #!/bin/sh
                        case "$1" in
                          *Username*) printf '%s' "$OSWL_GIT_USERNAME" ;;
                          *) printf '%s' "$OSWL_GIT_PASSWORD" ;;
                        esac
                        """, StandardCharsets.UTF_8);
                script.toFile().setExecutable(true, false);
            }
            restrictToOwner(script);
            return script;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(script);
            throw e;
        }
    }

    private static void restrictToOwner(Path script) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(script, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or non-POSIX filesystem
        }
    }
}
