package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * deps.dev version + advisory lookups, targeted at a wanted-list (E6) — deps.dev has no bulk dump
 * (E5.2), so unlike OSV/EPSS/KEV this source is skipped entirely without {@code --wanted}.
 * GetVersion/GetAdvisory shapes and the package-name percent-encoding rules mirror the live
 * {@code DepsDevClient} exactly (kept independent here so this plain-Java CLI has no dependency on
 * the Spring-managed client).
 *
 * <p><b>Deliberately not fetched</b> (unlike the live client): {@code latestVersion} and
 * {@code scorecardScore} — each needs 1-2 extra API calls per package (GetPackage / GetProject),
 * which multiplies request count across a wanted-list of possibly thousands of components. The
 * offline snapshot's {@code SnapshotVersion} fields for these are nullable, so this is a
 * completeness/runtime trade-off, not a schema break — see E5's implementation notes.
 */
final class DepsDevSource {

    private static final String BASE_URL = "https://api.deps.dev";
    private static final int CONCURRENCY = 8;

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    DepsDevSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record VersionRecord(String ecosystem, String name, String version,
                          List<String> licenses, List<String> advisoryKeys,
                          boolean isDefault, String deprecated) {}

    record AdvisoryRecord(String ghsaId, String title, List<String> aliases,
                           Double cvss3Score, String cvss3Vector) {}

    record Result(List<VersionRecord> versions, List<AdvisoryRecord> advisories, int failedVersionLookups) {}

    /** systems deps.dev actually supports; anything else in the wanted-list is silently skipped here. */
    private static final Set<String> SUPPORTED = Set.of("MAVEN", "NPM", "PYPI", "GO", "NUGET", "CARGO", "RUBYGEMS");

    Result fetch(List<WantedComponent> wanted) {
        List<WantedComponent> targets = wanted.stream()
                .filter(w -> SUPPORTED.contains(w.ecosystem().toUpperCase(java.util.Locale.ROOT)))
                .toList();
        System.err.println("[oswl-vdb] deps.dev: querying GetVersion for " + targets.size()
                + " components (of " + wanted.size() + " wanted, " + (wanted.size() - targets.size())
                + " on an unsupported deps.dev system)");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        List<Future<VersionRecord>> futures = new ArrayList<>(targets.size());
        try {
            for (WantedComponent w : targets) {
                futures.add(pool.submit(() -> getVersion(w)));
            }
            List<VersionRecord> versions = new ArrayList<>();
            int failed = 0;
            int done = 0;
            for (Future<VersionRecord> f : futures) {
                done++;
                if (done % 200 == 0) System.err.println("[oswl-vdb] deps.dev GetVersion: " + done + "/" + targets.size());
                try {
                    VersionRecord r = f.get(30, TimeUnit.SECONDS);
                    if (r != null) versions.add(r);
                    else failed++;
                } catch (Exception e) {
                    failed++;
                }
            }

            Set<String> ghsaIds = new LinkedHashSet<>();
            for (VersionRecord v : versions) ghsaIds.addAll(v.advisoryKeys());
            System.err.println("[oswl-vdb] deps.dev: querying GetAdvisory for " + ghsaIds.size() + " advisories");

            List<Future<AdvisoryRecord>> advisoryFutures = new ArrayList<>(ghsaIds.size());
            for (String id : ghsaIds) advisoryFutures.add(pool.submit(() -> getAdvisory(id)));
            List<AdvisoryRecord> advisories = new ArrayList<>();
            for (Future<AdvisoryRecord> f : advisoryFutures) {
                try {
                    AdvisoryRecord r = f.get(30, TimeUnit.SECONDS);
                    if (r != null) advisories.add(r);
                } catch (Exception ignored) {
                    // one bad advisory id must not fail the whole build
                }
            }
            return new Result(versions, advisories, failed);
        } finally {
            pool.shutdown();
        }
    }

    private VersionRecord getVersion(WantedComponent w) {
        try {
            String system = w.ecosystem().toUpperCase(java.util.Locale.ROOT);
            String encodedName = encodePackageName(system, w.name());
            String encodedVersion = URLEncoder.encode(w.version(), StandardCharsets.UTF_8).replace("+", "%20");
            URI uri = URI.create(BASE_URL + "/v3/systems/" + system + "/packages/" + encodedName
                    + "/versions/" + encodedVersion);
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) return null; // not found upstream — not an error
            if (resp.statusCode() / 100 != 2) {
                System.err.println("[oswl-vdb] deps.dev GetVersion " + w.name() + "@" + w.version()
                        + " -> HTTP " + resp.statusCode());
                return null;
            }
            JsonNode node = mapper.readTree(resp.body());
            List<String> licenses = new ArrayList<>();
            node.path("licenses").forEach(n -> licenses.add(n.asText()));
            List<String> advisoryKeys = new ArrayList<>();
            node.path("advisoryKeys").forEach(n -> {
                String id = n.path("id").asText(null);
                if (id != null && !id.isBlank()) advisoryKeys.add(id);
            });
            String deprecated = node.path("isDeprecated").asBoolean(false)
                    ? node.path("deprecatedReason").asText("deprecated") : null;
            return new VersionRecord(system, w.name(), w.version(), licenses, advisoryKeys,
                    node.path("isDefault").asBoolean(false), deprecated);
        } catch (IOException | InterruptedException e) {
            System.err.println("[oswl-vdb] deps.dev GetVersion " + w.name() + "@" + w.version()
                    + " failed: " + e.getMessage());
            return null;
        }
    }

    private AdvisoryRecord getAdvisory(String ghsaId) {
        try {
            URI uri = URI.create(BASE_URL + "/v3/advisories/" + URLEncoder.encode(ghsaId, StandardCharsets.UTF_8));
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return null;
            JsonNode node = mapper.readTree(resp.body());
            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(n -> aliases.add(n.asText()));
            Double score = node.path("cvss3Score").isMissingNode() || node.path("cvss3Score").isNull()
                    ? null : node.path("cvss3Score").asDouble();
            return new AdvisoryRecord(ghsaId, node.path("title").asText(null), aliases,
                    score, node.path("cvss3Vector").asText(null));
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private static String encodePackageName(String ecosystem, String name) {
        return switch (ecosystem) {
            case "MAVEN" -> name.replace(":", "%3A");
            case "NPM" -> name.replace("@", "%40").replace("/", "%2F");
            case "GO" -> name.replace("/", "%2F");
            default -> URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        };
    }
}
