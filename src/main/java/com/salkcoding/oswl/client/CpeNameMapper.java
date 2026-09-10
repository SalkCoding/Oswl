package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Best-effort mapping from a component name to candidate CPE vendor/product pairs.
 *
 * <p>The NVD API is CPE-based, so a package-manager coordinate (e.g. {@code openssl/1.1.1k})
 * must be converted to {@code cpe:2.3:a:<vendor>:<product>:<version>} before lookup.
 * Name matching is inherently noisy, so every candidate carries a confidence grade:
 * known libraries resolve with high confidence; heuristic guesses are marked low or medium
 * and surfaced in the UI as needing manual review.
 */
public final class CpeNameMapper {

    private CpeNameMapper() {}

    private static final Map<String, String[]> KNOWN = Map.ofEntries(
            Map.entry("openssl", new String[]{"openssl", "openssl"}),
            Map.entry("zlib", new String[]{"zlib", "zlib"}),
            Map.entry("libpng", new String[]{"libpng", "libpng"}),
            Map.entry("curl", new String[]{"curl", "curl"}),
            Map.entry("libcurl", new String[]{"curl", "curl"}),
            Map.entry("libxml2", new String[]{"xmlsoft", "libxml2"}),
            Map.entry("libxslt", new String[]{"xmlsoft", "libxslt"}),
            Map.entry("libssh2", new String[]{"libssh2", "libssh2"}),
            Map.entry("libssh", new String[]{"libssh", "libssh"}),
            Map.entry("libgit2", new String[]{"libgit2", "libgit2"}),
            Map.entry("libtiff", new String[]{"libtiff", "libtiff"}),
            Map.entry("libjpeg-turbo", new String[]{"libjpeg-turbo", "libjpeg-turbo"}),
            Map.entry("libwebp", new String[]{"webmproject", "libwebp"}),
            Map.entry("freetype", new String[]{"freetype", "freetype"}),
            Map.entry("harfbuzz", new String[]{"harfbuzz", "harfbuzz"}),
            Map.entry("pcre", new String[]{"pcre", "pcre"}),
            Map.entry("pcre2", new String[]{"pcre2", "pcre2"}),
            Map.entry("sqlite", new String[]{"sqlite", "sqlite"}),
            Map.entry("ffmpeg", new String[]{"ffmpeg", "ffmpeg"}),
            Map.entry("gnutls", new String[]{"gnutls", "gnutls"}),
            Map.entry("libgcrypt", new String[]{"gnupg", "libgcrypt"}),
            Map.entry("busybox", new String[]{"busybox", "busybox"}),
            Map.entry("openssh", new String[]{"openbsd", "openssh"}),
            Map.entry("gnupg", new String[]{"gnupg", "gnupg"}),
            Map.entry("libx11", new String[]{"x.org", "libx11"})
    );

    public record CpeCandidate(String vendor, String product, String version, MatchConfidence confidence) {}

    /**
     * Returns one or more CPE candidates for a component name/version.
     * Known C/C++ libraries are pinned to their canonical vendor/product pair with high
     * confidence; unknown names fall back to heuristic guesses with lower confidence.
     */
    public static List<CpeCandidate> infer(String name, String version) {
        String key = (name == null) ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (KNOWN.containsKey(key)) {
            String[] vp = KNOWN.get(key);
            return List.of(new CpeCandidate(vp[0], vp[1], version, MatchConfidence.HIGH));
        }

        List<CpeCandidate> candidates = new ArrayList<>();
        String product = key.replaceFirst("^lib", "");
        if (product.isBlank()) {
            product = key;
        }

        if (product.equals(key)) {
            candidates.add(new CpeCandidate(product, product, version, MatchConfidence.LOW));
        } else {
            candidates.add(new CpeCandidate(product, product, version, MatchConfidence.MEDIUM));
            candidates.add(new CpeCandidate(key, key, version, MatchConfidence.LOW));
        }
        return candidates;
    }
}
