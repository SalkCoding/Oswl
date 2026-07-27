package com.salkcoding.oswl.vdb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * E5's "재실행 가능성" requirement: caches downloaded upstream bytes under {@code --cache-dir} so a
 * re-run of {@code oswl-vdb build} (e.g. after tweaking {@code --wanted}) doesn't re-download
 * multi-hundred-MB OSV dumps. Entries older than {@link #MAX_AGE} are treated as stale and
 * re-fetched; with no {@code --cache-dir}, every call goes straight to the network.
 *
 * <p>{@code --offline-sources <dir>} (E5) uses this same class in {@code offlineOnly} mode: the
 * directory is treated exactly like a cache dir except a miss is a hard error instead of a
 * network fallback — the whole point is a build that never touches the network. The expected
 * filenames are this class's own cache-key convention, e.g. {@code osv-npm-all.zip},
 * {@code epss_scores-current.csv.gz}, {@code kev.json} — pre-populate a directory by running a
 * normal {@code --cache-dir} build once while online, then reuse that same directory offline.
 */
final class HttpCache {

    private static final Duration MAX_AGE = Duration.ofHours(20);

    private final Path cacheDir;
    private final boolean offlineOnly;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    HttpCache(Path cacheDir) {
        this(cacheDir, false);
    }

    HttpCache(Path cacheDir, boolean offlineOnly) {
        this.cacheDir = cacheDir;
        this.offlineOnly = offlineOnly;
    }

    byte[] getOrFetch(String cacheKey, String url) throws IOException, InterruptedException {
        if (cacheDir != null) {
            Path cached = cacheDir.resolve(cacheKey);
            if (Files.isRegularFile(cached)) {
                if (offlineOnly) {
                    System.err.println("[oswl-vdb] offline-sources hit: " + cacheKey);
                    return Files.readAllBytes(cached);
                }
                Instant mtime = Files.getLastModifiedTime(cached).toInstant();
                if (Duration.between(mtime, Instant.now()).compareTo(MAX_AGE) < 0) {
                    System.err.println("[oswl-vdb] cache hit: " + cacheKey);
                    return Files.readAllBytes(cached);
                }
            }
        }
        if (offlineOnly) {
            throw new IOException("--offline-sources is missing " + cacheKey + " under " + cacheDir
                    + " — pre-populate it with a normal --cache-dir build while online first");
        }
        System.err.println("[oswl-vdb] fetching " + url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IOException("Failed to fetch " + url + ": " + e.getMessage(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Fetch " + url + " returned HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (cacheDir != null) {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(cacheKey), body);
        }
        return body;
    }
}
