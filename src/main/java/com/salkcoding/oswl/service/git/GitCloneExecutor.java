package com.salkcoding.oswl.service.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git clone} with a clean HTTPS URL and credentials supplied via {@code GIT_ASKPASS}
 * (not embedded in the URL / argv).
 */
@Slf4j
@Component
public class GitCloneExecutor {

    private static final String ENV_USERNAME = "OSWL_GIT_USERNAME";
    private static final String ENV_PASSWORD = "OSWL_GIT_PASSWORD";

    public void clone(String repositoryUrl, GitCloneCredentials credentials, String branch,
                      Path targetDir, String jobId) throws Exception {
        Path askpass = writeAskpassScript();
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    "git", "clone", "--depth", "1", "--single-branch", "--quiet"
            ));
            if (branch != null && !branch.isBlank()) {
                cmd.addAll(List.of("--branch", branch));
            }
            cmd.add(repositoryUrl);
            cmd.add(targetDir.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");
            pb.environment().put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
            pb.environment().put(ENV_USERNAME, credentials.username());
            pb.environment().put(ENV_PASSWORD, credentials.password());

            log.info("[QuickImport][{}] Running: git clone --depth 1 {} (credentials via GIT_ASKPASS)",
                    jobId, repositoryUrl);

            Process proc = pb.start();
            final String[] outputHolder = {""};
            Thread outputReader = Thread.ofVirtual().start(() -> {
                try {
                    outputHolder[0] = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            });
            boolean finished = proc.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                proc.destroyForcibly();
                throw new RuntimeException("git clone timed out after 5 minutes");
            }
            try {
                outputReader.join(2_000);
            } catch (InterruptedException ignored) {
            }
            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                String safe = redactSecrets(outputHolder[0]);
                throw new RuntimeException("git clone failed (exit " + exitCode + "): " + safe.trim());
            }
        } finally {
            try {
                Files.deleteIfExists(askpass);
            } catch (IOException e) {
                log.debug("[QuickImport][{}] Could not delete GIT_ASKPASS script: {}", jobId, e.getMessage());
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
