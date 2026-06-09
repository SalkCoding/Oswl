package com.salkcoding.oswl.service.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Ensures all file access stays under a resolved clone root (symlink / traversal safe).
 */
public final class CloneRootPathGuard {

    private final Path rootReal;

    public CloneRootPathGuard(Path cloneRoot) throws IOException {
        if (!Files.isDirectory(cloneRoot)) {
            throw new IOException("Clone root is not a directory: " + cloneRoot);
        }
        this.rootReal = cloneRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    public Path root() {
        return rootReal;
    }

    /**
     * Resolves {@code path} to a real path and verifies it remains under {@link #root()}.
     */
    public Path verifyContained(Path path) throws IOException {
        Path real = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(rootReal)) {
            throw new SecurityException("Path escapes clone root: " + path);
        }
        return real;
    }

    /**
     * Resolves and creates the configured clone base directory.
     */
    public static Path resolveConfiguredBase(String configuredTempDir) throws IOException {
        String base = configuredTempDir != null && !configuredTempDir.isBlank()
                ? configuredTempDir
                : System.getProperty("java.io.tmpdir") + java.io.File.separator + "oswl-clones";
        Path parent = Path.of(base).normalize();
        Files.createDirectories(parent);
        return parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }
}
