package com.salkcoding.oswl.service.ingest.parser;
import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import org.jspecify.annotations.NonNull;
import lombok.extern.slf4j.Slf4j;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.util.*;
@Slf4j
public class ManifestDiscovery {
    private static final Set<String> MANIFEST_SKIP_DIRS = ManifestCollectRules.SKIP_DIRS;
    public ManifestIndex buildIndex(Path root) {
        Map<String, List<Path>> byFileName = new HashMap<>();
        Map<String, List<Path>> bySuffix = new HashMap<>();
        final Path rootReal;
        try {
            rootReal = new CloneRootPathGuard(root).root();
        } catch (IOException e) {
            log.warn("[DependencyParser] buildIndex: invalid clone root '{}': {}", root, e.getMessage());
            return new ManifestIndex(root, byFileName, bySuffix);
        }
        try {
            Files.walkFileTree(rootReal, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) {
                    if (!dir.equals(rootReal)) {
                        Path rel = rootReal.relativize(dir);
                        for (int i = 0; i < rel.getNameCount(); i++) {
                            if (MANIFEST_SKIP_DIRS.contains(rel.getName(i).toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) {
                    try {
                        Path fileReal = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                        if (!fileReal.startsWith(rootReal)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Path rel = rootReal.relativize(fileReal);
                        for (int i = 0; i < rel.getNameCount() - 1; i++) {
                            if (MANIFEST_SKIP_DIRS.contains(rel.getName(i).toString())) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        String name = fileReal.getFileName().toString();
                        if (ManifestCollectRules.EXACT_FILE_NAMES.contains(name)) {
                            byFileName.computeIfAbsent(name, k -> new ArrayList<>()).add(fileReal);
                        }
                        for (String suffix : ManifestCollectRules.FILE_SUFFIXES) {
                            if (name.endsWith(suffix)) {
                                bySuffix.computeIfAbsent(suffix, k -> new ArrayList<>()).add(fileReal);
                            }
                        }
                    } catch (IOException e) {
                        log.debug("[DependencyParser] skip file '{}': {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[DependencyParser] buildIndex error under '{}': {}", root, e.getMessage());
        }
        Comparator<Path> byPath = Comparator.comparing(Path::toString);
        byFileName.values().forEach(paths -> paths.sort(byPath));
        bySuffix.values().forEach(paths -> paths.sort(byPath));
        return new ManifestIndex(rootReal, byFileName, bySuffix);
    }

    public List<Path> indexByNames(ManifestIndex index, String... fileNames) {
        List<Path> result = new ArrayList<>();
        for (String name : fileNames) {
            result.addAll(index.byFileName().getOrDefault(name, List.of()));
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    public List<Path> indexBySuffix(ManifestIndex index, String suffix) {
        return index.bySuffix().getOrDefault(suffix, List.of());
    }

    public boolean hasCsprojFiles(ManifestIndex index) {
        for (Path p : indexBySuffix(index, ".csproj")) {
            if (index.root().relativize(p).getNameCount() <= 8) {
                return true;
            }
        }
        return false;
    }
}
