package com.salkcoding.oswl.service.ai;

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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile Process process;
    private volatile String activeModelFile;

    public EmbeddedAiService(
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.dir:embedded-ai}") String dirPath,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.port:11435}") int port,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.context-size:4096}") int contextSize) {
        this.dirPath = dirPath;
        this.port = port;
        this.contextSize = contextSize;
    }

    @Value
    @Builder
    public static class EmbeddedAiStatus {
        boolean binaryFound;
        boolean running;
        String binaryPath;
        String modelFile;
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
                .modelFile(activeModelFile != null ? activeModelFile
                        : pickPreferredModel().map(p -> p.getFileName().toString()).orElse(null))
                .availableModels(findModels().stream().map(p -> p.getFileName().toString()).toList())
                .baseUrl(baseUrl())
                .modelsDir(resolveDir().toAbsolutePath().toString())
                .build();
    }

    public boolean isRunning() {
        Process p = process;
        if (p != null && p.isAlive()) return true;
        // A server started outside OsWL (or from a previous run) also counts
        return healthCheck();
    }

    /**
     * Starts the sidecar with the preferred model and waits until it answers /health.
     *
     * @throws IllegalStateException with a user-facing reason when it cannot start
     */
    public synchronized void start() {
        if (isRunning()) return;

        Path binary = findServerBinary().orElseThrow(() -> new IllegalStateException(
                "llama-server binary not found. Place llama-server(.exe) in " + resolveDir().toAbsolutePath()
                        + " or on PATH (download: https://github.com/ggml-org/llama.cpp/releases)."));
        Path model = pickPreferredModel().orElseThrow(() -> new IllegalStateException(
                "No .gguf model found in " + resolveDir().toAbsolutePath()
                        + ". Expected qwen3-1.7b-q4_k_m.gguf (or gemma-3-1b-it-Q4_K_M.gguf)."));

        try {
            Path logFile = resolveDir().resolve("llama-server.log");
            ProcessBuilder pb = new ProcessBuilder(
                    binary.toString(),
                    "-m", model.toString(),
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
            activeModelFile = model.getFileName().toString();
            log.info("[EmbeddedAI] Started llama-server pid={} model={} port={}",
                    process.pid(), activeModelFile, port);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to launch llama-server: " + e.getMessage(), e);
        }

        if (!waitUntilHealthy(Duration.ofSeconds(90))) {
            stop();
            throw new IllegalStateException(
                    "llama-server did not become healthy within 90s. Check "
                            + resolveDir().resolve("llama-server.log").toAbsolutePath());
        }
        log.info("[EmbeddedAI] llama-server healthy at {}", baseUrl());
    }

    public synchronized void stop() {
        Process p = process;
        process = null;
        activeModelFile = null;
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
        log.info("[EmbeddedAI] llama-server stopped");
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    // ── Discovery ────────────────────────────────────────────────────────

    private Path resolveDir() {
        return Path.of(dirPath);
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
