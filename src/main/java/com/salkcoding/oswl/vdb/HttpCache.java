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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Caches downloaded upstream bytes under {@code --cache-dir} so a
 * re-run of {@code oswl-vdb build} (e.g. after tweaking {@code --wanted}) doesn't re-download
 * multi-hundred-MB OSV dumps. Entries older than {@link #MAX_AGE} are treated as stale and
 * re-fetched; with no {@code --cache-dir}, every call goes straight to the network.
 *
 * <p>{@code --offline-sources <dir>} uses this same class in {@code offlineOnly} mode: the
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

    /**
     * The upstream's own {@code Last-Modified} date, when the caller needs to know how fresh the
     * fetched content actually is rather than assuming "today" — a bulk dump can sit unchanged
     * on the server for a long time (verified: OSV's Debian/Ubuntu {@code all.zip} dumps haven't
     * moved since October 2024, while npm's updates same-day) and a source that just stamps
     * {@code LocalDate.now()} would silently defeat the air-gapped staleness-warning system.
     * {@code lastModified} is {@code null} when unavailable (server didn't send the header, or a
     * cache hit predating this field's introduction with no sidecar file) — callers should treat
     * that as "freshness unknown", not "fresh".
     */
    record FetchResult(byte[] body, LocalDate lastModified) {}

    byte[] getOrFetch(String cacheKey, String url) throws IOException, InterruptedException {
        return getOrFetchWithLastModified(cacheKey, url).body();
    }

    FetchResult getOrFetchWithLastModified(String cacheKey, String url) throws IOException, InterruptedException {
        Path sidecar = cacheDir != null ? cacheDir.resolve(cacheKey + ".lastmodified") : null;
        if (cacheDir != null) {
            Path cached = cacheDir.resolve(cacheKey);
            if (Files.isRegularFile(cached)) {
                if (offlineOnly) {
                    System.err.println("[oswl-vdb] offline-sources hit: " + cacheKey);
                    return new FetchResult(Files.readAllBytes(cached), readSidecar(sidecar));
                }
                Instant mtime = Files.getLastModifiedTime(cached).toInstant();
                if (Duration.between(mtime, Instant.now()).compareTo(MAX_AGE) < 0) {
                    System.err.println("[oswl-vdb] cache hit: " + cacheKey);
                    return new FetchResult(Files.readAllBytes(cached), readSidecar(sidecar));
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
        LocalDate lastModified = parseLastModified(response.headers().firstValue("Last-Modified").orElse(null));
        if (cacheDir != null) {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(cacheKey), body);
            if (lastModified != null && sidecar != null) {
                Files.writeString(sidecar, lastModified.toString());
            }
        }
        return new FetchResult(body, lastModified);
    }

    private static LocalDate readSidecar(Path sidecar) {
        if (sidecar == null || !Files.isRegularFile(sidecar)) {
            return null;
        }
        try {
            return LocalDate.parse(Files.readString(sidecar).strip());
        } catch (IOException | DateTimeParseException e) {
            return null;
        }
    }

    private static LocalDate parseLastModified(String httpDate) {
        if (httpDate == null || httpDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
