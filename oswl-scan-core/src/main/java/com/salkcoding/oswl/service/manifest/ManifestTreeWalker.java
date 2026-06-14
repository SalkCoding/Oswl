package com.salkcoding.oswl.service.manifest;

import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursively collects manifest file paths under a clone root, honouring skip-dir rules.
 */
@Slf4j
public final class ManifestTreeWalker {

    private ManifestTreeWalker() {
    }

    /**
     * Finds all files whose name is in {@code fileNames} under {@code root},
     * skipping any path segment listed in {@code skipDirs}.
     */
    public static List<Path> walkManifests(Path root, Set<String> fileNames, Set<String> skipDirs) {
        List<Path> result = new ArrayList<>();
        final Path rootReal;
        try {
            rootReal = new CloneRootPathGuard(root).root();
        } catch (IOException e) {
            log.warn("[DependencyParser] walkManifests: invalid clone root '{}': {}", root, e.getMessage());
            return result;
        }
        try {
            Files.walkFileTree(rootReal, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(rootReal)) {
                        Path rel = rootReal.relativize(dir);
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (skipDirs.contains(rel.getName(i).toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!fileReal.startsWith(rootReal)) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (!fileNames.contains(fileReal.getFileName().toString())) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = rootReal.relativize(fileReal);
                        for (int i = 0; i < rel.getNameCount() - 1; i++) {
                            if (skipDirs.contains(rel.getName(i).toString())) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        result.add(fileReal);
                    } catch (IOException e) {
                        log.debug("[DependencyParser] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[DependencyParser] walkManifests error under '{}': {}", root, e.getMessage());
        }
        return result;
    }
}
