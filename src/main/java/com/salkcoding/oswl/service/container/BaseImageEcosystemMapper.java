package com.salkcoding.oswl.service.container;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a Docker base image reference ({@code repository[:tag]}) to the internal ecosystem tag
 * for that OS release, when — and only when — the repository name and tag unambiguously name a
 * known Linux distribution release (ROADMAP A3).
 *
 * <p>Deliberately conservative: only images whose <em>repository name itself</em> is a known
 * distro (e.g. {@code debian}, {@code alpine}) are mapped. Images like {@code python:3.11-slim}
 * or {@code node:20-alpine} are very likely Debian/Alpine underneath, but guessing that from the
 * tag string would be exactly the "assume the label, not the data" mistake ROADMAP A0 was
 * written to stop repeating — so those return empty rather than a guess.
 *
 * <p>The returned tag already carries the version, e.g. {@code "DEBIAN:11"} or
 * {@code "ALPINE:V3.14"} — {@link com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService}
 * reconstructs the exact-cased OSV ecosystem string ({@code "Debian:11"}, {@code "Alpine:v3.14"})
 * from this at query time, since {@code Library.ecosystem} is always stored upper-cased.
 */
public final class BaseImageEcosystemMapper {

    private BaseImageEcosystemMapper() {
    }

    /** Debian codename -> major version, for the well-known still-relevant stable releases. */
    private static final Map<String, String> DEBIAN_CODENAMES = Map.of(
            "buster", "10", "bullseye", "11", "bookworm", "12", "trixie", "13");

    /** Ubuntu codename -> release version, for the well-known LTS releases. */
    private static final Map<String, String> UBUNTU_CODENAMES = Map.of(
            "bionic", "18.04", "focal", "20.04", "jammy", "22.04", "noble", "24.04");

    /**
     * @param repository the image repository name, e.g. {@code "debian"} in {@code debian:11}
     *                    (registry/namespace prefixes like {@code library/} already stripped)
     * @param tag         the image tag, e.g. {@code "11"}; {@code null}/blank treated as
     *                    {@code "latest"}
     * @return the internal ecosystem tag (e.g. {@code "DEBIAN:11"}), or {@code null} if this
     *         repository/tag isn't a recognized, version-pinned distro release
     */
    public static String map(String repository, String tag) {
        if (repository == null) {
            return null;
        }
        String repo = repository.strip().toLowerCase(Locale.ROOT);
        int slash = repo.lastIndexOf('/');
        if (slash >= 0) {
            repo = repo.substring(slash + 1);
        }
        String t = (tag == null || tag.isBlank()) ? "latest" : tag.strip().toLowerCase(Locale.ROOT);

        return switch (repo) {
            case "debian" -> debian(t);
            case "ubuntu" -> ubuntu(t);
            case "alpine" -> alpine(t);
            default -> null;
        };
    }

    private static String debian(String tag) {
        String version = DEBIAN_CODENAMES.get(tag);
        if (version == null && tag.matches("\\d+(\\.\\d+)?")) {
            version = tag.split("\\.")[0]; // "11.5" -> "11" — OSV's Debian ecosystem is major-version only
        }
        return version != null ? "DEBIAN:" + version : null;
    }

    private static String ubuntu(String tag) {
        String version = UBUNTU_CODENAMES.get(tag);
        if (version != null) {
            // Every codename in UBUNTU_CODENAMES is an LTS release, and OSV's real ecosystem/bucket
            // name for those carries a literal ":LTS" suffix (e.g. "Ubuntu:22.04:LTS" — confirmed
            // against the live osv-vulnerabilities GCS bucket listing, 2026-08-13). Omitting it here
            // used to still resolve for live queries (OSV's query API tolerates the missing
            // suffix) but 404s against the bulk dump bucket, which only has the ":LTS" folder.
            return "UBUNTU:" + version + ":LTS";
        }
        if (tag.matches("\\d{2}\\.\\d{2}")) {
            // Ubuntu's public numbering scheme: LTS releases are always an even year's April
            // (YY.04); anything else (odd years, or the October interim releases) is a non-LTS
            // release, whose OSV bucket has no ":LTS" suffix (e.g. "Ubuntu:23.10").
            String[] parts = tag.split("\\.");
            boolean isLts = "04".equals(parts[1]) && Integer.parseInt(parts[0]) % 2 == 0;
            return "UBUNTU:" + tag + (isLts ? ":LTS" : "");
        }
        return null;
    }

    private static String alpine(String tag) {
        // Alpine tags are "3.14", "3.14.2" (patch releases share the minor's advisory feed) — OSV's
        // Alpine ecosystem is major.minor only.
        if (tag.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            String[] parts = tag.split("\\.");
            return "ALPINE:V" + parts[0] + "." + parts[1];
        }
        return null;
    }
}
