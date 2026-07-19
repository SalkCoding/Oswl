package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiPreferences;
import com.salkcoding.oswl.repository.AiPreferencesRepository;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages an embedded llama.cpp {@code llama-server} sidecar so OsWL can run a
 * local LLM out of the box (no cloud key, CPU-only).
 *
 * Expected layout in {@code oswl.ai.embedded.dir} (default {@code ./embedded-ai}):
 * <pre>
 *   embedded-ai/
 *     llama-server(.exe)     — llama.cpp server binary (or on PATH)
 *     qwen3-1.7b-q4_k_m.gguf — preferred model
 *     gemma-3-1b-it-Q4_K_M.gguf — low-spec fallback
 * </pre>
 * The started server exposes an OpenAI-compatible endpoint at
 * {@code http://127.0.0.1:port/v1}, which is registered as the LOCAL provider.
 */
@Slf4j
@Service
public class EmbeddedAiService {

    /** Model filename fragments in preference order (first match wins). */
    private static final List<String> MODEL_PREFERENCE = List.of("qwen3", "gemma-3-1b", "gemma3");

    private final String dirPath;
    private final int port;
    private final int contextSize;
    private final AiPreferencesRepository preferencesRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile Process process;
    private volatile String activeModelFile;
    private volatile boolean fallbackUsed;
    private volatile String lastError;

    public EmbeddedAiService(
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.dir:embedded-ai}") String dirPath,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.port:11435}") int port,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.context-size:4096}") int contextSize,
            AiPreferencesRepository preferencesRepository) {
        this.dirPath = dirPath;
        this.port = port;
        this.contextSize = contextSize;
        this.preferencesRepository = preferencesRepository;
    }

    @Value
    @Builder
    public static class EmbeddedAiStatus {
        boolean binaryFound;
        boolean running;
        String binaryPath;
        /** Selected/preferred model for UI display (persisted preference or first preferred .gguf). */
        String modelFile;
        /** Actually running model file (null when stopped). */
        String activeModel;
        /** True when the running model differs from the requested/first-preferred one. */
        boolean fallbackUsed;
        /** Last start-failure reason (null after a successful start). */
        String lastError;
        List<String> availableModels;
        String baseUrl;
        String modelsDir;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port + "/v1";
    }

    /** Model name reported to the OpenAI-compatible API (file stem, e.g. {@code qwen3-1.7b-q4_k_m}). */
    public String modelName() {
        String file = activeModelFile != null ? activeModelFile
                : pickPreferredModel().map(p -> p.getFileName().toString()).orElse("embedded");
        return file.toLowerCase(Locale.ROOT).endsWith(".gguf")
                ? file.substring(0, file.length() - 5)
                : file;
    }

    public synchronized EmbeddedAiStatus status() {
        Optional<Path> binary = findServerBinary();
        return EmbeddedAiStatus.builder()
                .binaryFound(binary.isPresent())
                .running(isRunning())
                .binaryPath(binary.map(Path::toString).orElse(null))
                .modelFile(preferredModelFile())
                .activeModel(activeModelFile)
                .fallbackUsed(fallbackUsed)
                .lastError(lastError)
                .availableModels(findModels().stream().map(p -> p.getFileName().toString()).toList())
                .baseUrl(baseUrl())
                .modelsDir(displayDir())
                .build();
    }

    public boolean isRunning() {
        Process p = process;
        if (p != null && p.isAlive()) return true;
        // A server started outside OsWL (or from a previous run) also counts
        return healthCheck();
    }

    /** Effective sidecar directory (persisted UI override or the configured default). */
    public Path effectiveDir() {
        return resolveDir();
    }

    /**
     * Starts the sidecar with the preferred model and waits until it answers /health.
     *
     * @throws IllegalStateException with a user-facing reason when it cannot start
     */
    public synchronized void start() {
        start(null);
    }

    /**
     * Starts the sidecar and waits until it answers /health. When {@code requestedModel}
     * names a .gguf present in the sidecar directory it is tried first; the persisted UI
     * preference, {@link #MODEL_PREFERENCE} order and any remaining .gguf follow. A model
     * that fails to launch or never becomes healthy is skipped in favor of the next
     * candidate (auto-fallback).
     *
     * @throws IllegalStateException with a user-facing reason when no candidate starts
     */
    public synchronized void start(String requestedModel) {
        if (isRunning()) return;

        Path binary = findServerBinary().orElseThrow(() -> new IllegalStateException(
                "llama-server binary not found. Place llama-server(.exe) in " + resolveDir().toAbsolutePath()
                        + " or on PATH (download: https://github.com/ggml-org/llama.cpp/releases)."));
        List<Path> candidates = startCandidates(requestedModel);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No .gguf model found in " + resolveDir().toAbsolutePath()
                            + ". Expected qwen3-1.7b-q4_k_m.gguf (or gemma-3-1b-it-Q4_K_M.gguf).");
        }

        lastError = null;
        fallbackUsed = false;
        Path firstChoice = candidates.get(0);
        for (Path model : candidates) {
            String name = model.getFileName().toString();
            try {
                launch(binary, model);
            } catch (IOException e) {
                lastError = "Failed to launch llama-server with " + name + ": " + e.getMessage();
                log.warn("[EmbeddedAI] {} — trying next model", lastError);
                continue;
            }
            if (waitUntilHealthy(Duration.ofSeconds(90))) {
                activeModelFile = name;
                fallbackUsed = !model.equals(firstChoice);
                lastError = null;
                log.info("[EmbeddedAI] llama-server healthy at {} model={}{}",
                        baseUrl(), name, fallbackUsed ? " (fallback)" : "");
                return;
            }
            Process p = process;
            String reason = p != null && p.isAlive()
                    ? "llama-server did not become healthy within 90s with " + name + "."
                    : "llama-server exited early with " + name + ".";
            killProcess();
            String tail = logTail();
            lastError = tail.isEmpty() ? reason : reason + " Log: " + tail;
            log.warn("[EmbeddedAI] {} — trying next model", reason);
        }
        activeModelFile = null;
        throw new IllegalStateException(lastError != null ? lastError
                : "llama-server could not start with any available model.");
    }

    /**
     * Launches llama-server for one model candidate. Binary, model and log paths are
     * absolute-normalized because the child process CWD is the sidecar directory.
     */
    private void launch(Path binary, Path model) throws IOException {
        Path logFile = resolveDir().resolve("llama-server.log").toAbsolutePath().normalize();
        ProcessBuilder pb = new ProcessBuilder(
                binary.toAbsolutePath().normalize().toString(),
                "-m", model.toAbsolutePath().normalize().toString(),
                "--host", "127.0.0.1",
                "--port", String.valueOf(port),
                "-c", String.valueOf(contextSize),
                // Thinking models (Qwen3) burn max_tokens on reasoning_content and
                // return empty content for short completions — disable reasoning.
                "--reasoning-budget", "0",
                "--no-webui")
                .directory(resolveDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        process = pb.start();
        log.info("[EmbeddedAI] Started llama-server pid={} model={} port={}",
                process.pid(), model.getFileName(), port);
    }

    public synchronized void stop() {
        killProcess();
        activeModelFile = null;
        fallbackUsed = false;
        log.info("[EmbeddedAI] llama-server stopped");
    }

    /** Kills the child process (if any) without touching status fields. */
    private void killProcess() {
        Process p = process;
        process = null;
        if (p == null || !p.isAlive()) return;
        p.destroy();
        try {
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    // ── Discovery ────────────────────────────────────────────────────────

    /** Effective sidecar directory: persisted UI override wins over the yaml/env default. */
    private Path resolveDir() {
        String override = persistedEmbeddedDir();
        return Path.of(override != null ? override : dirPath);
    }

    /**
     * Folder shown in the UI. Prefer a path relative to the working directory (e.g. "embedded-ai")
     * so we never leak an absolute machine path; fall back to the effective configured value.
     */
    private String displayDir() {
        Path dir = resolveDir();
        try {
            Path base = Path.of("").toAbsolutePath();
            Path rel = base.relativize(dir.toAbsolutePath());
            String s = rel.toString();
            if (!s.isBlank() && !s.startsWith("..")) {
                return "./" + s.replace('\\', '/');
            }
        } catch (Exception ignored) {
            // fall through to the effective value
        }
        return dir.toString();
    }

    /** Persisted UI override for the sidecar directory (null = use the configured default). */
    private String persistedEmbeddedDir() {
        return preferencesRepository.findById(AiPreferences.SINGLETON_ID)
                .map(AiPreferences::getEmbeddedDir)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /** Persisted UI preference for the model file name (null = built-in preference order). */
    private String persistedEmbeddedModel() {
        return preferencesRepository.findById(AiPreferences.SINGLETON_ID)
                .map(AiPreferences::getEmbeddedModel)
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /** Model shown in the UI: persisted preference when set, otherwise the first preferred .gguf. */
    private String preferredModelFile() {
        String persisted = persistedEmbeddedModel();
        if (persisted != null) return persisted;
        return pickPreferredModel().map(p -> p.getFileName().toString()).orElse(null);
    }

    /**
     * Ordered model candidates for a start attempt: the explicitly requested file first
     * (only matched against files actually present, so no path traversal), then the
     * persisted UI preference, then {@link #MODEL_PREFERENCE} order, then any remaining .gguf.
     */
    private List<Path> startCandidates(String requestedModel) {
        List<Path> models = findModels();
        LinkedHashSet<Path> ordered = new LinkedHashSet<>();
        for (String wanted : new String[]{requestedModel, persistedEmbeddedModel()}) {
            if (wanted == null || wanted.isBlank()) continue;
            String name = wanted.strip();
            models.stream()
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(ordered::add);
        }
        for (String fragment : MODEL_PREFERENCE) {
            for (Path model : models) {
                if (model.getFileName().toString().toLowerCase(Locale.ROOT).contains(fragment)) {
                    ordered.add(model);
                }
            }
        }
        ordered.addAll(models);
        return List.copyOf(ordered);
    }

    /** Last few lines of the sidecar log, for surfacing startup failures in the UI. */
    private String logTail() {
        Path logFile = resolveDir().resolve("llama-server.log");
        try {
            if (!Files.isRegularFile(logFile)) return "";
            List<String> lines = Files.readAllLines(logFile);
            String tail = lines.subList(Math.max(0, lines.size() - 5), lines.size()).stream()
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.length() > 200 ? s.substring(0, 200) : s)
                    .collect(Collectors.joining(" | "));
            return tail.length() > 600 ? tail.substring(tail.length() - 600) : tail;
        } catch (IOException e) {
            return "";
        }
    }

    private Optional<Path> findServerBinary() {
        String exe = isWindows() ? "llama-server.exe" : "llama-server";
        Path dir = resolveDir();
        for (Path candidate : List.of(dir.resolve(exe), dir.resolve("bin").resolve(exe))) {
            if (Files.isRegularFile(candidate)) return Optional.of(candidate);
        }
        // PATH lookup
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(java.io.File.pathSeparator)) {
                if (entry.isBlank()) continue;
                Path candidate = Path.of(entry.strip()).resolve(exe);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private List<Path> findModels() {
        Path dir = resolveDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[EmbeddedAI] Could not list models in {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private Optional<Path> pickPreferredModel() {
        List<Path> models = findModels();
        for (String fragment : MODEL_PREFERENCE) {
            for (Path model : models) {
                if (model.getFileName().toString().toLowerCase(Locale.ROOT).contains(fragment)) {
                    return Optional.of(model);
                }
            }
        }
        return models.stream().findFirst();
    }

    // ── Health ───────────────────────────────────────────────────────────

    private boolean waitUntilHealthy(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p == null || !p.isAlive()) return false;
            if (healthCheck()) return true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean healthCheck() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
