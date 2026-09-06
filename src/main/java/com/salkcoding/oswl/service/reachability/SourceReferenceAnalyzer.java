package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.enums.Reachability;
import com.salkcoding.oswl.service.manifest.ManifestCollectRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;

/** Indexes bounded source references once per repository. References do not prove runtime execution. */
@Slf4j
@Component
public class SourceReferenceAnalyzer {

    private static final int MAX_EVIDENCE = 5;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024L;

    private static final Set<String> PY_EXTENSIONS = Set.of(".py");
    private static final Set<String> JS_EXTENSIONS = Set.of(".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx");

    // "import X", "import X.Y.Z", "import X as x", "import X, Y, Z" — captures the whole
    // comma-separated tail so mergeImportTail can split it (a single regex can't repeat-capture).
    private static final Pattern PY_IMPORT_LINE = Pattern.compile("(?m)^\\s*import\\s+(.+)$");
    // "from X import Y" / "from X.Y import Z" — a leading '.' (relative import) is excluded by
    // requiring the first character to be a letter or underscore.
    private static final Pattern PY_FROM_LINE = Pattern.compile("(?m)^\\s*from\\s+([A-Za-z_][\\w.]*)\\s+import\\s");

    public record ReferenceEvidence(String referencingFile, String importedName) {}

    public record AnalysisResult(Reachability reachability, List<ReferenceEvidence> evidence) {
        static AnalysisResult of(Reachability reachability) {
            return new AnalysisResult(reachability, List.of());
        }
    }

    public enum Language { PYTHON, JAVASCRIPT }
    public record Index(Map<Language, Map<String, List<ReferenceEvidence>>> references,
                        Map<Language, Integer> files, Set<String> limitations,
                        long bytesRead, long elapsedMillis) {
        public AnalysisResult match(Language language, Set<String> candidates) {
            List<ReferenceEvidence> found = candidates.stream()
                    .flatMap(name -> references.getOrDefault(language, Map.of()).getOrDefault(name, List.of()).stream())
                    .limit(MAX_EVIDENCE).toList();
            // Static absence cannot prove runtime unreachability: dynamic/transitive imports
            // and distribution-name guesses remain outside this analysis.
            return found.isEmpty() ? AnalysisResult.of(Reachability.UNKNOWN)
                    : new AnalysisResult(Reachability.REACHABLE, found);
        }
        public String description(Language language, boolean matched, boolean missingMapping) {
            int count = files.getOrDefault(language, 0);
            String reason = matched ? "REFERENCE_FOUND" : missingMapping ? "NO_PACKAGE_MAPPING"
                    : count == 0 ? "NO_LANGUAGE_FILES" : "ABSENCE_NOT_PROVEN";
            return new com.salkcoding.oswl.dto.scan.SourceAnalysisDetails(language.name(), count, bytesRead,
                    matched ? (language == Language.PYTHON ? "HEURISTIC_REFERENCE" : "STATIC_REFERENCE") : "UNKNOWN",
                    !limitations.isEmpty(), reason, limitations.stream().sorted().toList()).toJson();
        }
    }

    public Index index(Path root) { return index(root, 20_000, 64L * 1024 * 1024, 30_000); }
    public Index index(Path root, int maxFiles, long maxBytes, long maxMillis) {
        long started = System.nanoTime();
        Map<Language, Map<String, List<ReferenceEvidence>>> refs = new EnumMap<>(Language.class);
        Map<Language, Integer> counts = new EnumMap<>(Language.class);
        Set<String> limitations = new HashSet<>();
        long[] bytes = {0}; int[] visited = {0};
        if (root == null || !Files.isDirectory(root)) limitations.add("NO_SOURCE_ROOT");
        else try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private boolean exhausted() {
                    if (Thread.currentThread().isInterrupted() || (System.nanoTime()-started)/1_000_000 >= maxMillis) {
                        limitations.add("TIME_LIMIT"); return true;
                    }
                    if (++visited[0] > maxFiles) { limitations.add("FILE_LIMIT"); return true; }
                    return false;
                }
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (exhausted()) return FileVisitResult.TERMINATE;
                    return !dir.equals(root) && ManifestCollectRules.SKIP_DIRS.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (exhausted()) return FileVisitResult.TERMINATE;
                    String ext = extensionOf(path);
                    Language language = PY_EXTENSIONS.contains(ext) ? Language.PYTHON
                            : JS_EXTENSIONS.contains(ext) ? Language.JAVASCRIPT : null;
                    if (language == null) return FileVisitResult.CONTINUE;
                    if (!attrs.isRegularFile()) { limitations.add("NON_REGULAR_FILE"); return FileVisitResult.CONTINUE; }
                    if (attrs.size() > MAX_FILE_BYTES) { limitations.add("OVERSIZE_FILE"); return FileVisitResult.CONTINUE; }
                    if (bytes[0] + attrs.size() > maxBytes) { limitations.add("BYTE_LIMIT"); return FileVisitResult.TERMINATE; }
                    try (var input = Files.newInputStream(path)) {
                        byte[] data = input.readNBytes((int) MAX_FILE_BYTES + 1);
                        if (data.length > MAX_FILE_BYTES || bytes[0] + data.length > maxBytes) {
                            limitations.add("BYTE_LIMIT"); return FileVisitResult.TERMINATE;
                        }
                        bytes[0] += data.length;
                        String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
                        counts.merge(language, 1, Integer::sum);
                        Set<String> imports = language == Language.PYTHON ? extractPythonImports(content) : extractJsImports(content);
                        String relative = root.relativize(path).toString().replace('\\', '/');
                        Map<String, List<ReferenceEvidence>> perLanguage = refs.computeIfAbsent(language, key -> new HashMap<>());
                        for (String name : imports) {
                            List<ReferenceEvidence> evidence = perLanguage.computeIfAbsent(name, key -> new ArrayList<>());
                            if (evidence.size() < MAX_EVIDENCE) evidence.add(new ReferenceEvidence(relative, name));
                        }
                    } catch (IOException e) { limitations.add("UNREADABLE_FILE"); }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
                    limitations.add("UNREADABLE_PATH"); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) { limitations.add("INCOMPLETE_WALK"); }
        Map<Language, Map<String, List<ReferenceEvidence>>> immutable = new EnumMap<>(Language.class);
        refs.forEach((language, values) -> {
            Map<String, List<ReferenceEvidence>> copy = new HashMap<>();
            values.forEach((name, evidence) -> copy.put(name, List.copyOf(evidence)));
            immutable.put(language, Map.copyOf(copy));
        });
        return new Index(Map.copyOf(immutable), Map.copyOf(counts), Set.copyOf(limitations), bytes[0],
                (System.nanoTime()-started)/1_000_000);
    }
    /** Compatibility entry point. Ecosystem-aware callers use Index.match. */
    public AnalysisResult analyze(Path root, Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return AnalysisResult.of(Reachability.UNKNOWN);
        Index index = index(root);
        for (Language language : Language.values()) {
            AnalysisResult result = index.match(language, candidates);
            if (result.reachability() == Reachability.REACHABLE) return result;
        }
        return AnalysisResult.of(Reachability.UNKNOWN);
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> extractPythonImports(String content) {
        content = SourceImportSyntax.pythonCode(content);
        Set<String> names = new LinkedHashSet<>();
        Matcher importMatcher = PY_IMPORT_LINE.matcher(content);
        while (importMatcher.find()) {
            for (String entry : importMatcher.group(1).split(",")) {
                String moduleRef = entry.strip().split("\\s+as\\s+")[0].strip();
                addTopLevel(names, moduleRef);
            }
        }
        Matcher fromMatcher = PY_FROM_LINE.matcher(content);
        while (fromMatcher.find()) {
            addTopLevel(names, fromMatcher.group(1));
        }
        return names;
    }

    private static void addTopLevel(Set<String> names, String dottedModule) {
        if (dottedModule == null || dottedModule.isEmpty()) return;
        int dot = dottedModule.indexOf('.');
        String top = dot < 0 ? dottedModule : dottedModule.substring(0, dot);
        if (!top.isEmpty() && (Character.isLetter(top.charAt(0)) || top.charAt(0) == '_')) {
            names.add(top);
        }
    }

    private static Set<String> extractJsImports(String content) {
        Set<String> names = new LinkedHashSet<>();
        for (String specifier : SourceImportSyntax.javascriptSpecifiers(content)) addNpmTopLevel(names, specifier);
        return names;
    }

    private static void addNpmTopLevel(Set<String> names, String specifier) {
        if (specifier == null || specifier.isEmpty()) return;
        // Relative ("./x", "../x") and absolute ("/x") specifiers are project-local, not packages.
        if (specifier.startsWith(".") || specifier.startsWith("/")) return;
        String[] segments = specifier.split("/");
        if (segments.length == 0) return;
        String top = segments[0].startsWith("@") && segments.length > 1
                ? segments[0] + "/" + segments[1]
                : segments[0];
        if (!top.isEmpty()) {
            names.add(top);
        }
    }
}
