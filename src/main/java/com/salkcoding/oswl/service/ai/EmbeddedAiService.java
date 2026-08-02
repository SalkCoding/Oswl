package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.repository.AiPreferencesRepository;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * </pre>
 * Any other {@code .gguf} file dropped in this directory is picked up too — see
 * {@link #MODEL_PREFERENCE}.
 * The started server exposes an OpenAI-compatible endpoint at
 * {@code http://127.0.0.1:port/v1}, which is registered as the LOCAL provider.
 */
@Slf4j
@Service
public class EmbeddedAiService {

    /**
     * Model filename fragment preferred when the sidecar directory has more than one
     * {@code .gguf} — any other file the user drops in is still picked up as a fallback
     * candidate by {@code startCandidates()}/{@code pickPreferredModel()}, this list only
     * orders preference among what's present.
     */
    private static final List<String> MODEL_PREFERENCE = List.of("qwen3");

    /**
     * Default model auto-fetched when the sidecar directory has no .gguf at all, so a fresh
     * on-premise install works out of the box with just {@code java -jar app.jar} — no
     * separate download step or build tooling required. Apache 2.0 licensed (see
     * THIRD_PARTY_LICENSES.md), so bundling/auto-fetching it carries no extra redistribution
     * obligation.
     */
    private static final String DEFAULT_MODEL_FILE = "qwen3-1.7b-q4_k_m.gguf";

    private final String dirPath;
    private final int port;
    private final int contextSize;
    /** -1 = offload as many layers as the build supports (passed as {@code -ngl 999}); 0 = CPU only. */
    private final int gpuLayers;
    /** 0 = let llama.cpp auto-detect thread count (no {@code -t} flag). */
    private final int threads;
    /** &gt;1 enables {@code --parallel N --cont-batching} so concurrent AI calls aren't serialized on the server. */
    private final int parallelSlots;
    private final boolean flashAttn;
    /** &lt;=0 disables the {@code --cache-reuse} flag. */
    private final int cacheReuse;
    /** Space-separated extra CLI args appended verbatim (SYSTEM_ADMIN-only server config, not user input). */
    private final String extraArgs;
    private final int startupTimeoutSeconds;
    private final AiPreferencesRepository preferencesRepository;

    // ── G4: default-model source, made configurable so a self-hosted mirror (or an air-gapped
    // pre-baked path) can replace the upstream asset without a code change.
    // url/sha256/size-bytes are always a matched set — see the application.yaml comment. ──
    private final String defaultModelUrl;
    private final String defaultModelSha256;
    private final long defaultModelSizeBytes;
    /** Retried once if {@link #defaultModelUrl} fails; blank/equal to the primary disables the retry. */
    private final String fallbackModelUrl;
    /**
     * Air-gapped hosts must never reach out for the model — {@link #downloadDefaultModel()}
     * refuses instead of failing on a network error the operator can't act on.
     */
    private final boolean airgapped;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile Process process;
    private volatile String activeModelFile;
    private volatile boolean fallbackUsed;
    private volatile String lastError;
    /** Set by {@link #stop()} so a {@link #start(String)} blocked in the health wait aborts. */
    private volatile boolean stopRequested;

    // ── Default-model download state (read by status() for UI polling; written only from
    // downloadDefaultModel(), which never holds the `this` monitor so status() stays live) ──
    private volatile boolean downloading;
    private volatile long downloadedBytes;
    private volatile long downloadTotalBytes;

    public EmbeddedAiService(
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.dir:embedded-ai}") String dirPath,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.port:11435}") int port,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.context-size:8192}") int contextSize,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.gpu-layers:-1}") int gpuLayers,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.threads:0}") int threads,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.parallel-slots:4}") int parallelSlots,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.flash-attn:true}") boolean flashAttn,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.cache-reuse:256}") int cacheReuse,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.extra-args:}") String extraArgs,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.startup-timeout-seconds:120}") int startupTimeoutSeconds,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.default-model-url:https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf}") String defaultModelUrl,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.default-model-sha256:d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5}") String defaultModelSha256,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.default-model-size-bytes:1282439264}") long defaultModelSizeBytes,
            @org.springframework.beans.factory.annotation.Value("${oswl.ai.embedded.fallback-model-url:}") String fallbackModelUrl,
            @org.springframework.beans.factory.annotation.Value("${oswl.airgapped.enabled:false}") boolean airgapped,
            AiPreferencesRepository preferencesRepository) {
        this.dirPath = dirPath;
        this.port = port;
        this.contextSize = contextSize;
        this.gpuLayers = gpuLayers;
        this.threads = threads;
        this.parallelSlots = parallelSlots;
        this.flashAttn = flashAttn;
        this.cacheReuse = cacheReuse;
        this.extraArgs = extraArgs;
        this.defaultModelUrl = defaultModelUrl;
        this.defaultModelSha256 = defaultModelSha256;
        this.defaultModelSizeBytes = defaultModelSizeBytes;
        this.fallbackModelUrl = fallbackModelUrl;
        this.airgapped = airgapped;
        this.startupTimeoutSeconds = startupTimeoutSeconds;
        this.preferencesRepository = preferencesRepository;
    }

    @Value
    @Builder
    public static class EmbeddedAiStatus {
        boolean binaryFound;
        boolean running;
        /** True when the port answers /health but the process was not started by this OsWL instance. */
        boolean external;
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
        /** True while the default Qwen3 model is being fetched (see {@link #downloadDefaultModel()}). */
        boolean downloading;
        long downloadedBytes;
        long downloadTotalBytes;
        /**
         * True when {@code oswl.airgapped.enabled} is set — the UI then tells the operator to
         * place a .gguf themselves instead of promising an automatic download that cannot happen.
         */
        boolean airgapped;
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
        boolean childAlive = process != null && process.isAlive();
        boolean healthy = childAlive || healthCheck();
        return EmbeddedAiStatus.builder()
                .binaryFound(binary.isPresent())
                .running(healthy)
                // A server started outside OsWL (or orphaned by a previous run) answers
                // /health but is not our child — stop() cannot kill it, so flag it for the UI.
                .external(healthy && !childAlive)
                .binaryPath(binary.map(Path::toString).orElse(null))
                .modelFile(preferredModelFile())
                .activeModel(activeModelFile)
                .fallbackUsed(fallbackUsed)
                .lastError(lastError)
                .availableModels(findModels().stream().map(p -> p.getFileName().toString()).toList())
                .baseUrl(baseUrl())
                .modelsDir(displayDir())
                .downloading(downloading)
                .downloadedBytes(downloadedBytes)
                .downloadTotalBytes(downloadTotalBytes)
                .airgapped(airgapped)
                .build();
    }

    /** True when the sidecar directory has no {@code .gguf} file yet (fresh install). */
    public boolean needsModelDownload() {
        return findModels().isEmpty();
    }

    public boolean isDownloading() {
        return downloading;
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
        stopRequested = false;

        Path binary = findServerBinary().orElseThrow(() -> new IllegalStateException(
                "llama-server binary not found. Place llama-server(.exe) in " + resolveDir().toAbsolutePath()
                        + " or on PATH (download: https://github.com/ggml-org/llama.cpp/releases)."));
        List<Path> candidates = startCandidates(requestedModel);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No .gguf model found in " + resolveDir().toAbsolutePath()
                            + ". Expected qwen3-1.7b-q4_k_m.gguf (or any other .gguf file).");
        }

        lastError = null;
        fallbackUsed = false;
        Path firstChoice = candidates.get(0);
        for (Path model : candidates) {
            String name = model.getFileName().toString();

            // GPU offload first (if configured); a launch or health-check failure falls back
            // to a CPU-only retry of the *same* model before moving on to the next candidate —
            // VRAM exhaustion shouldn't cost us a model we'd otherwise run fine on CPU.
            if (tryLaunchModel(binary, model, name, gpuLayers != 0)) {
                activeModelFile = name;
                fallbackUsed = !model.equals(firstChoice);
                lastError = null;
                return;
            }
            if (stopRequested) {
                activeModelFile = null;
                lastError = "Start cancelled: llama-server was stopped while starting up.";
                throw new IllegalStateException(lastError);
            }
            if (gpuLayers != 0) {
                log.warn("[EmbeddedAI] GPU-accelerated start failed for {} — retrying CPU-only", name);
                if (tryLaunchModel(binary, model, name, false)) {
                    activeModelFile = name;
                    fallbackUsed = !model.equals(firstChoice);
                    lastError = null;
                    return;
                }
                if (stopRequested) {
                    activeModelFile = null;
                    lastError = "Start cancelled: llama-server was stopped while starting up.";
                    throw new IllegalStateException(lastError);
                }
            }
            log.warn("[EmbeddedAI] {} — trying next model", lastError);
        }
        activeModelFile = null;
        throw new IllegalStateException(lastError != null ? lastError
                : "llama-server could not start with any available model.");
    }

    /**
     * One launch + health-wait attempt for a single model. Returns {@code false} (with
     * {@link #lastError} set) on any failure — launch error, health-check timeout, or an
     * early exit — instead of throwing, so {@link #start(String)} can decide whether to
     * retry (CPU fallback, next model) or give up.
     */
    private boolean tryLaunchModel(Path binary, Path model, String name, boolean useGpu) {
        try {
            launch(binary, model, useGpu);
        } catch (IOException e) {
            lastError = "Failed to launch llama-server with " + name
                    + (useGpu ? " (GPU)" : " (CPU)") + ": " + e.getMessage();
            return false;
        }
        if (waitUntilHealthy(Duration.ofSeconds(startupTimeoutSeconds))) {
            log.info("[EmbeddedAI] llama-server healthy at {} model={} gpu={}", baseUrl(), name, useGpu);
            return true;
        }
        if (stopRequested) {
            return false;
        }
        Process p = process;
        String reason = p != null && p.isAlive()
                ? "llama-server did not become healthy within " + startupTimeoutSeconds + "s with " + name
                        + (useGpu ? " (GPU)" : " (CPU)") + "."
                : "llama-server exited early with " + name + (useGpu ? " (GPU)" : " (CPU)") + ".";
        killProcess();
        String tail = logTail();
        lastError = tail.isEmpty() ? reason : reason + " Log: " + tail;
        return false;
    }

    /**
     * Downloads the default Qwen3 model into the sidecar directory, verifying its SHA256
     * checksum before making it visible under its final name. Deliberately <b>not</b>
     * {@code synchronized} — a 1.2GB download can take minutes, and {@link #status()} (also
     * synchronized) must keep responding to polling for the {@code downloading}/progress
     * fields the whole time. Concurrent callers are guarded by the {@code downloading} flag
     * instead: a second call while one is in flight returns immediately.
     *
     * @throws IllegalStateException with a user-facing reason on network failure or a
     *         checksum mismatch (the partial/corrupt file is removed either way)
     */
    public void downloadDefaultModel() {
        // Air-gapped installs have no route to the model host. Failing here with an explicit,
        // actionable reason beats letting the HTTP call time out and surfacing a bare connect
        // error the operator cannot act on — the fix is always "put the .gguf in the folder".
        // Both entry points funnel through here (boot prefetch and the manual Start button),
        // so this is the single choke point rather than a per-caller check.
        if (airgapped) {
            lastError = "Air-gapped mode is enabled, so the model cannot be downloaded. "
                    + "Place a .gguf model file in " + resolveDir().toAbsolutePath() + " manually.";
            throw new IllegalStateException(lastError);
        }
        if (downloading) return;
        downloading = true;
        downloadedBytes = 0;
        downloadTotalBytes = defaultModelSizeBytes;
        try {
            attemptDownloadWithFallback();
        } finally {
            downloading = false;
        }
    }

    /**
     * G4: tries {@link #defaultModelUrl} first; if it fails for any reason (network error,
     * non-200, or a checksum mismatch — a corrupted/tampered primary asset is exactly the case
     * where falling back to the original upstream host is most valuable) and a different
     * {@link #fallbackModelUrl} is configured, retries once against it. Both attempts verify
     * against the same {@link #defaultModelSha256}, since the fallback is expected to be a
     * byte-identical copy re-hosted elsewhere.
     */
    private void attemptDownloadWithFallback() {
        Path dir = resolveDir();
        Path dest = dir.resolve(DEFAULT_MODEL_FILE);
        Path partFile = dir.resolve(DEFAULT_MODEL_FILE + ".part");
        boolean hasFallback = fallbackModelUrl != null && !fallbackModelUrl.isBlank()
                && !fallbackModelUrl.equals(defaultModelUrl);
        try {
            attemptDownload(defaultModelUrl, dir, dest, partFile);
        } catch (IllegalStateException primaryFailure) {
            if (!hasFallback) throw primaryFailure;
            log.warn("[EmbeddedAI] Default model download from primary URL failed ({}) — retrying fallback {}",
                    primaryFailure.getMessage(), fallbackModelUrl);
            downloadedBytes = 0;
            attemptDownload(fallbackModelUrl, dir, dest, partFile);
        }
    }

    private void attemptDownload(String url, Path dir, Path dest, Path partFile) {
        try {
            Files.createDirectories(dir);
            log.info("[EmbeddedAI] Downloading default model {} ({} MB) from {}",
                    DEFAULT_MODEL_FILE, defaultModelSizeBytes / 1024 / 1024, url);

            HttpClient downloadClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(60))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Model download failed: HTTP " + response.statusCode() + " from " + url);
            }
            response.headers().firstValueAsLong("Content-Length")
                    .ifPresent(len -> downloadTotalBytes = len);

            MessageDigest digest = sha256Digest();
            try (DigestInputStream in = new DigestInputStream(response.body(), digest);
                 OutputStream out = Files.newOutputStream(partFile)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    downloadedBytes += read;
                }
            }

            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!actualSha256.equalsIgnoreCase(defaultModelSha256)) {
                Files.deleteIfExists(partFile);
                throw new IllegalStateException("Downloaded model failed checksum verification "
                        + "(expected " + defaultModelSha256 + ", got " + actualSha256 + ") — deleted, please retry.");
            }
            Files.move(partFile, dest, StandardCopyOption.REPLACE_EXISTING);
            log.info("[EmbeddedAI] Default model downloaded and verified: {}", dest);
        } catch (IOException | InterruptedException e) {
            try { Files.deleteIfExists(partFile); } catch (IOException ignored) { /* best effort cleanup */ }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Model download failed: " + e.getMessage(), e);
        }
    }

    /**
     * G5: async entry point for boot-time prefetch (see {@code EmbeddedAiBootstrapService}) —
     * download-only, so a fresh boot never silently starts the sidecar or flips the active AI
     * provider. Exceptions are swallowed (logged) since there is no HTTP caller here to report
     * {@code lastError} to; a failed prefetch just means the user's next manual Start retries it.
     */
    @org.springframework.scheduling.annotation.Async
    public void downloadDefaultModelAsync() {
        try {
            downloadDefaultModel();
        } catch (Exception e) {
            log.warn("[EmbeddedAI] Background default-model download failed: {}", e.getMessage());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    /**
     * Launches llama-server for one model candidate. Binary, model and log paths are
     * absolute-normalized because the child process CWD is the sidecar directory.
     *
     * @param useGpu when {@code false}, {@code -ngl} is omitted regardless of the configured
     *               {@code gpu-layers} — used for the CPU-only retry after a GPU start failure
     */
    private void launch(Path binary, Path model, boolean useGpu) throws IOException {
        Path logFile = resolveDir().resolve("llama-server.log").toAbsolutePath().normalize();

        List<String> cmd = new ArrayList<>();
        cmd.add(binary.toAbsolutePath().normalize().toString());
        cmd.add("-m");
        cmd.add(model.toAbsolutePath().normalize().toString());
        cmd.add("--host");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(String.valueOf(port));
        cmd.add("-c");
        cmd.add(String.valueOf(contextSize));
        // Thinking models (Qwen3) burn max_tokens on reasoning_content and
        // return empty content for short completions — disable reasoning.
        cmd.add("--reasoning-budget");
        cmd.add("0");
        cmd.add("--no-webui");

        if (useGpu && gpuLayers != 0) {
            cmd.add("-ngl");
            cmd.add(gpuLayers < 0 ? "999" : String.valueOf(gpuLayers));
        }
        if (threads > 0) {
            cmd.add("-t");
            cmd.add(String.valueOf(threads));
        }
        if (parallelSlots > 1) {
            cmd.add("--parallel");
            cmd.add(String.valueOf(parallelSlots));
            cmd.add("--cont-batching");
            logSlotContextWarningIfNeeded();
        }
        if (flashAttn) {
            cmd.add("-fa");
        }
        if (cacheReuse > 0) {
            cmd.add("--cache-reuse");
            cmd.add(String.valueOf(cacheReuse));
        }
        for (String arg : splitExtraArgs()) {
            cmd.add(arg);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(resolveDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile());
        process = pb.start();
        log.info("[EmbeddedAI] Started llama-server pid={} model={} port={} gpu={} ngl={} parallel={} threads={} flashAttn={} cacheReuse={}",
                process.pid(), model.getFileName(), port, useGpu,
                useGpu && gpuLayers != 0 ? (gpuLayers < 0 ? "999(auto)" : String.valueOf(gpuLayers)) : "off",
                parallelSlots, threads > 0 ? String.valueOf(threads) : "auto", flashAttn, cacheReuse);
    }

    /** Non-empty, whitespace-split tokens from {@link #extraArgs} (empty list when unset). */
    private List<String> splitExtraArgs() {
        if (extraArgs == null || extraArgs.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : extraArgs.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * {@code --parallel N} divides the total {@code -c} context across N slots — a
     * misconfigured combination (large parallel count, small context) silently starves each
     * slot. Warn loudly rather than let it surface later as truncated/garbled completions.
     */
    private void logSlotContextWarningIfNeeded() {
        int slotContext = contextSize / parallelSlots;
        log.info("[EmbeddedAI] slotContext={} (contextSize={} / parallel={})", slotContext, contextSize, parallelSlots);
        if (slotContext < 2048) {
            log.warn("[EmbeddedAI] slotContext={} is below 2048 — raise oswl.ai.embedded.context-size or "
                    + "lower oswl.ai.embedded.parallel-slots", slotContext);
        }
    }

    public synchronized void stop() {
        stopRequested = true;
        killProcess();
        activeModelFile = null;
        fallbackUsed = false;
        // Wake a start() blocked in the health wait so it can abort promptly.
        notifyAll();
        if (healthCheck()) {
            log.info("[EmbeddedAI] Port {} still answers /health after stop — an external "
                    + "llama-server not managed by OsWL is running and was left untouched", port);
        } else {
            log.info("[EmbeddedAI] llama-server stopped");
        }
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

    /**
     * Polls /health until the timeout. Sleeps via {@link #wait(long)} so the monitor is
     * released between probes — {@link #status()} and {@link #stop()} stay responsive
     * during the (up to 90s per model) startup window, and {@code stop()} wakes us at once.
     */
    private synchronized boolean waitUntilHealthy(Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Process p = process;
            if (p == null || !p.isAlive()) return false;
            if (healthCheck()) return true;
            try {
                wait(1000);
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
