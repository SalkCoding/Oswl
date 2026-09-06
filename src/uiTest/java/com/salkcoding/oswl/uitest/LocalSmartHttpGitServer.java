package com.salkcoding.oswl.uitest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A real git smart-HTTP server (git-http-backend, spawned as a CGI subprocess per request) bound
 * to loopback, so {@code GitCloneExecutor}'s real {@code git clone} subprocess does actual clone
 * work — process spawn, HTTP round trips, pack negotiation — instead of failing instantly on an
 * unrecognized host. One bare repository backs every request regardless of the owner/repo path
 * in the URL, since load tests only need many distinct-looking clone targets, not many distinct
 * repositories.
 *
 * <p>Dumb HTTP (a plain static file server over the bare repo) was tried first and rejected: it
 * cannot serve a shallow clone ({@code --depth 1}, which {@link com.salkcoding.oswl.service.git.
 * GitCloneExecutor} always uses) — {@code fatal: dumb http transport does not support shallow
 * capabilities}. git-http-backend (smart HTTP) has no such limitation and ships with any git
 * installation, so no extra dependency is needed.
 *
 * <p>{@code GitCloneExecutor} hardcodes {@code https://} for every provider, so this server can't
 * be reached by its bare {@code http://} URL directly. {@link #writeGitConfigGlobalRewrite} writes
 * a {@code url.<http>.insteadOf = <https>} rewrite rule to a file; when that file's path is set as
 * the {@code GIT_CONFIG_GLOBAL} environment variable for the JVM running the test (see
 * {@code uiTest} task in build.gradle), every {@code git} subprocess spawned by that JVM —
 * including the real one inside {@code GitCloneExecutor} — picks it up automatically, with zero
 * changes to production code.
 */
final class LocalSmartHttpGitServer implements AutoCloseable {

    private final HttpServer httpServer;
    private final String gitHttpBackendPath;
    private final Path bareRepoDir;
    private final int port;

    private LocalSmartHttpGitServer(HttpServer httpServer, String gitHttpBackendPath,
                                     Path bareRepoDir, int port) {
        this.httpServer = httpServer;
        this.gitHttpBackendPath = gitHttpBackendPath;
        this.bareRepoDir = bareRepoDir;
        this.port = port;
    }

    /**
     * Creates a throwaway bare repository (one commit, a minimal manifest) and starts serving it
     * on loopback. {@code perRequestDelayMs} is added before every CGI response so a clone takes
     * long enough for concurrent HikariCP/web-request sampling to observe something — real clones
     * of a repo this small would otherwise finish in single-digit milliseconds.
     */
    static LocalSmartHttpGitServer start(Path workDir, long perRequestDelayMs) throws IOException, InterruptedException {
        return start(workDir, perRequestDelayMs, Map.of("package.json", "{\"name\":\"oswl-load-test\",\"version\":\"1.0.0\"}"));
    }

    /**
     * Same as {@link #start(Path, long)}, but commits {@code files} (relative path -> content)
     * instead of the default single manifest — for tests that need real source alongside the
     * manifest (e.g. {@code SourceReachabilityService} analysis, which has nothing to find in a
     * manifest-only tree).
     */
    static LocalSmartHttpGitServer start(Path workDir, long perRequestDelayMs, Map<String, String> files)
            throws IOException, InterruptedException {
        String backend = resolveGitHttpBackend();
        Path bareRepo = createBareRepoWithOneCommit(workDir, files);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", new CgiHandler(backend, bareRepo, perRequestDelayMs));
        server.start();

        return new LocalSmartHttpGitServer(server, backend, bareRepo, server.getAddress().getPort());
    }

    int port() {
        return port;
    }

    /** {@code host} to register on a self-hosted {@code UserVcsConnection} so parseRepoUrl finds it. */
    String host() {
        return "127.0.0.1:" + port;
    }

    /**
     * Writes {@code [url "http://127.0.0.1:<port>/"] insteadOf = https://127.0.0.1:<port>/} so a
     * git subprocess that inherits {@code GIT_CONFIG_GLOBAL=<target>} clones over plain HTTP
     * instead of attempting (and failing) a TLS handshake against this HTTP-only test server.
     */
    void writeGitConfigGlobalRewrite(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, """
                [url "http://127.0.0.1:%d/"]
                	insteadOf = https://127.0.0.1:%d/
                """.formatted(port, port), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        httpServer.stop(0);
    }

    private static Path createBareRepoWithOneCommit(Path workDir, Map<String, String> files)
            throws IOException, InterruptedException {
        Path source = workDir.resolve("source-repo");
        Files.createDirectories(source);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path target = source.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }

        run(source, "git", "init", "--quiet", "-b", "main");
        run(source, "git", "config", "user.email", "loadtest@oswl.local");
        run(source, "git", "config", "user.name", "OsWL Load Test");
        run(source, "git", "add", ".");
        run(source, "git", "commit", "--quiet", "-m", "init");

        Path bare = workDir.resolve("repo.git");
        run(workDir, "git", "clone", "--quiet", "--bare", source.toString(), bare.toString());
        run(bare, "git", "update-server-info");
        return bare;
    }

    private static void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("Command failed: " + String.join(" ", cmd) + "\n" + output);
        }
    }

    /** Locates git-http-backend inside the running git installation's exec-path. */
    private static String resolveGitHttpBackend() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("git", "--exec-path").redirectErrorStream(true).start();
        String execPath = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("git --exec-path failed: " + execPath);
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path backend = Path.of(execPath, "git-http-backend" + (windows ? ".exe" : ""));
        if (!Files.isRegularFile(backend)) {
            throw new IOException("git-http-backend not found at " + backend);
        }
        return backend.toString();
    }

    /**
     * Minimal CGI/1.1 gateway: maps one HTTP request to one git-http-backend subprocess
     * invocation, translating headers/query/body into the CGI environment-variable contract and
     * parsing the "Status:"-prefixed CGI response back into a real HTTP response. Every request's
     * repo path is rewritten to the single bare repo this server actually has on disk.
     */
    private static final class CgiHandler implements com.sun.net.httpserver.HttpHandler {

        private static final Pattern REPO_PATH = Pattern.compile("^/[^/]+/[^/]+\\.git(/.*)?$");

        private final String backend;
        private final Path bareRepo;
        private final long delayMs;

        CgiHandler(String backend, Path bareRepo, long delayMs) {
            this.backend = backend;
            this.bareRepo = bareRepo;
            this.delayMs = delayMs;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                String rawPath = exchange.getRequestURI().getRawPath();
                String query = exchange.getRequestURI().getRawQuery();
                String pathInfo = rewriteToBareRepo(rawPath);

                ProcessBuilder pb = new ProcessBuilder(backend);
                pb.directory(bareRepo.getParent().toFile());
                Map<String, String> env = pb.environment();
                env.put("GIT_PROJECT_ROOT", bareRepo.getParent().toAbsolutePath().toString());
                env.put("GIT_HTTP_EXPORT_ALL", "1");
                env.put("PATH_INFO", pathInfo);
                env.put("QUERY_STRING", query == null ? "" : query);
                env.put("REQUEST_METHOD", exchange.getRequestMethod());
                env.put("SERVER_PROTOCOL", "HTTP/1.1");
                env.put("GATEWAY_INTERFACE", "CGI/1.1");
                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                if (contentType != null) env.put("CONTENT_TYPE", contentType);
                String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
                env.put("CONTENT_LENGTH", contentLength == null ? "0" : contentLength);

                Process proc = pb.start();
                Thread feeder = Thread.ofVirtual().start(() -> {
                    try (var body = exchange.getRequestBody(); var stdin = proc.getOutputStream()) {
                        body.transferTo(stdin);
                    } catch (IOException ignored) {
                    }
                });
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                proc.getInputStream().transferTo(stdout);
                try {
                    feeder.join();
                    if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    proc.destroyForcibly();
                }
                writeCgiResponse(exchange, stdout.toByteArray());
            } finally {
                exchange.close();
            }
        }

        private static String rewriteToBareRepo(String rawPath) {
            Matcher m = REPO_PATH.matcher(rawPath);
            if (m.matches()) {
                String suffix = m.group(1);
                return "/repo.git" + (suffix == null ? "" : suffix);
            }
            return rawPath;
        }

        private static void writeCgiResponse(HttpExchange exchange, byte[] cgiOutput) throws IOException {
            int headerEnd = indexOfDoubleCrlf(cgiOutput);
            String headerBlock = new String(cgiOutput, 0, Math.max(headerEnd, 0), StandardCharsets.ISO_8859_1);
            byte[] body = headerEnd < 0 ? cgiOutput
                    : java.util.Arrays.copyOfRange(cgiOutput, headerEnd + 4, cgiOutput.length);

            int status = 200;
            for (String line : headerBlock.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Status")) {
                    status = Integer.parseInt(value.split(" ")[0]);
                } else {
                    exchange.getResponseHeaders().add(name, value);
                }
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        private static int indexOfDoubleCrlf(byte[] data) {
            for (int i = 0; i + 3 < data.length; i++) {
                if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
