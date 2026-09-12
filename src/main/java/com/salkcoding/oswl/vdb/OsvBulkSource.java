package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * OSV bulk vulnerability dumps (per-ecosystem {@code all.zip} on the public GCS bucket), re-indexed
 * from vuln-unit to component-unit against a wanted-list — full re-indexing without a
 * wanted-list is a combinatorial explosion, so it is deliberately out of scope.
 *
 * <p>Coverage: an {@code affected[]} entry is resolved via the union of its enumerated versions
 * and supported ranges ({@link OsvRangeEvaluator}). Anything
 * neither path can confidently resolve is <b>never</b> silently treated as "not affected" — it's
 * counted in {@code unresolvedKeys} and surfaced in the bundle's {@code meta.json} coverage
 * stats, per the plan's explicit requirement that "no data" and "confirmed clean" must stay
 * distinguishable.
 *
 * <p><b>Debian/Ubuntu's bulk dumps are stale — this is a real, currently-unavoidable gap, not a
 * bug in this class.</b> Verified against the live {@code osv-vulnerabilities} GCS bucket
 * (2026-08-13): {@code Debian:11/all.zip} and {@code Ubuntu:22.04:LTS/all.zip} both carry an HTTP
 * {@code Last-Modified} of October 2024 — roughly 22 months old at the time of writing — while
 * {@code npm/all.zip} updates same-day. A cross-check against OSV's live query API for the exact
 * same package/version found 53 CVEs online vs. 27 in the bulk dump, and every one of the 26
 * missing entries turned out to be genuinely absent from the downloaded {@code .json} files
 * (confirmed by direct inspection, not a parsing miss on this class's part) — i.e. the gap is
 * OSV's bulk export for these ecosystems having gone stale, not anything resolvable here. The
 * fetched {@code asOfByBucket} date now reflects the real upstream {@code Last-Modified} (see
 * {@link HttpCache#getOrFetchWithLastModified}), so the existing air-gapped staleness-warning
 * system ({@code oswl.airgapped.staleness-warn-days}/{@code staleness-critical-days}) correctly
 * flags Debian/Ubuntu coverage as critically stale rather than reporting it as current — that
 * warning is the honest, currently-correct outcome, not something to suppress. Revisit if OSV
 * resumes publishing fresh Debian/Ubuntu bulk dumps.
 */
final class OsvBulkSource {

    /**
     * our normalized ecosystem (AirgappedSnapshotService.componentKey's output) -> OSV GCS bucket folder.
     *
     * <p>CONAN is deliberately absent: as of 2026-08 the OSV GCS bucket has no
     * {@code ConanCenter/} folder at all (and osv.dev lists zero ConanCenter advisories), so
     * there is no dump to fetch. Wanted CONAN components therefore fall through to
     * {@code unresolved.jsonl} — surfaced as "no data" rather than "confirmed clean", which is
     * the honest state until OSV starts publishing ConanCenter entries. Add the mapping here
     * once {@code https://storage.googleapis.com/osv-vulnerabilities/ConanCenter/all.zip} exists.
     *
     * <p>Debian/Ubuntu (version-suffixed, e.g. {@code "DEBIAN:11"}) aren't listed here — there's
     * one bucket per release, so a fixed map can't enumerate them. {@link #resolveBucket} handles
     * those via {@link VulnerabilityEnrichmentService#osPackageOsvEcosystem}, the same
     * internal↔OSV-casing reconstruction the live per-component query path already uses —
     * kept as one shared implementation rather than a second hardcoded prefix table here.
     */
    private static final Map<String, String> ECOSYSTEM_TO_BUCKET = Map.of(
            "MAVEN", "Maven",
            "NPM", "npm",
            "PYPI", "PyPI",
            "GO", "Go",
            "CARGO", "crates.io",
            "NUGET", "NuGet",
            "RUBYGEMS", "RubyGems",
            "COMPOSER", "Packagist"
    );

    private final ObjectMapper mapper;

    OsvBulkSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Resolves an internal ecosystem tag to its OSV GCS bucket folder name, or {@code null} if
     * there's nothing to fetch for it.
     *
     * <p>Alpine (like Debian/Ubuntu) isn't in the fixed map above because it's version-suffixed
     * (one bucket per release, e.g. {@code "ALPINE:V3.14"} -> {@code "Alpine:v3.14"}) — falls
     * through to {@code osPackageOsvEcosystem} the same way Debian/Ubuntu do. Unlike Debian/
     * Ubuntu, Alpine's advisories carry no enumerated {@code versions[]}, only {@code ECOSYSTEM}-
     * typed ranges, so {@link #resolveAffected} compares those with {@link ApkVersionComparator}
     * instead of leaving them unresolved.
     */
    private static String resolveBucket(String ecosystem) {
        String fixed = ECOSYSTEM_TO_BUCKET.get(ecosystem);
        if (fixed != null) {
            return fixed;
        }
        return VulnerabilityEnrichmentService.osPackageOsvEcosystem(ecosystem);
    }

    /** {@code unresolvedKeys} (not just a count) so callers can tell resolved from unresolved
     * components — a wanted key that's neither in {@code vulnsByComponentKey} nor
     * {@code unresolvedKeys} was confirmed clean (its package never appeared in the ecosystem's
     * full OSV dump, which — since the dump is complete — means OSV has no known vulnerability
     * for it at all, not "we didn't check"). */
    record Result(Map<String, List<SnapshotVuln>> vulnsByComponentKey, Set<String> unresolvedKeys,
                  Map<String, LocalDate> asOfByBucket) {}

    Result fetch(List<WantedComponent> wanted, Set<String> ecosystemFilter, HttpCache cache) throws Exception {
        // ecosystem (normalized) -> name -> versions wanted
        Map<String, Map<String, Set<String>>> byEcosystem = new LinkedHashMap<>();
        for (WantedComponent w : wanted) {
            String eco = AirgappedSnapshotService.normalizeEcosystem(w.ecosystem());
            if (resolveBucket(eco) == null) continue;
            if (ecosystemFilter != null && !ecosystemFilter.contains(eco)) continue;
            byEcosystem.computeIfAbsent(eco, e -> new LinkedHashMap<>())
                    .computeIfAbsent(w.name(), n -> new LinkedHashSet<>())
                    .add(w.version());
        }

        Map<String, List<SnapshotVuln>> result = new LinkedHashMap<>();
        Set<String> unresolvedKeys = new LinkedHashSet<>();
        Map<String, LocalDate> asOfByBucket = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Set<String>>> ecoEntry : byEcosystem.entrySet()) {
            String ecosystem = ecoEntry.getKey();
            String bucket = resolveBucket(ecosystem);
            Map<String, Set<String>> namesWanted = ecoEntry.getValue();
            Map<String, List<String>> aliases = indexNames(ecosystem, namesWanted);
            System.err.println("[oswl-vdb] OSV: fetching " + bucket + "/all.zip for " + namesWanted.size() + " wanted package name(s)");
            // Debian/Ubuntu bucket names contain ':' (e.g. "Debian:11", "Ubuntu:22.04:LTS"), which
            // is a reserved character in Windows filenames — sanitize the whole cache key, not
            // just '/', so this doesn't only work on Linux/Mac dev machines.
            HttpCache.FetchResult fetched = cache.getOrFetchWithLastModified(
                    "osv-" + bucket.replaceAll("[^A-Za-z0-9.-]", "_") + "-all.zip",
                    "https://storage.googleapis.com/osv-vulnerabilities/" + bucket + "/all.zip");
            byte[] zipBytes = fetched.body();
            // The upstream's own Last-Modified, not "today" — a bulk dump can sit unchanged on
            // the server for a long time (Debian/Ubuntu's haven't moved since Oct 2024, verified
            // 2026-08-13, while npm's updates same-day) and stamping "now" here would silently
            // defeat the air-gapped staleness-warning system for exactly the ecosystems where it
            // matters most. Missing source dates cannot establish a dated snapshot.
            LocalDate asOf = fetched.lastModified();
            if (asOf == null) {
                throw new IOException("OSV " + bucket + "/all.zip has no Last-Modified source date; "
                        + "refresh the source cache and its date metadata before building a snapshot");
            }
            asOfByBucket.put(ecosystem, asOf);

            int entriesScanned = 0;
            Map<String, String> originalDigests = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".json")) continue;
                    entriesScanned++;
                    if (entriesScanned % 20000 == 0) {
                        System.err.println("[oswl-vdb] OSV " + bucket + ": scanned " + entriesScanned + " entries");
                    }
                    byte[] content = zis.readAllBytes();
                    JsonNode original = readOriginal(content);
                    String digest = OsvOriginalDigest.of(original);
                    String previous = originalDigests.putIfAbsent(original.path("id").asText(), digest);
                    if (previous != null) {
                        if (!previous.equals(digest)) {
                            throw new IOException("Conflicting OSV originals share an advisory ID; source coverage is unknown");
                        }
                        continue;
                    }
                    processOriginal(original, ecosystem, namesWanted, result, unresolvedKeys, aliases);
                }
            }
            if (entriesScanned == 0) {
                throw new IOException("OSV " + bucket + "/all.zip contains no advisory records; coverage is unknown");
            }
            System.err.println("[oswl-vdb] OSV " + bucket + ": " + entriesScanned + " vuln entries scanned");
        }
        return new Result(result, unresolvedKeys, asOfByBucket);
    }

    private void processVulnEntry(byte[] content, String ecosystem, Map<String, Set<String>> namesWanted,
                                   Map<String, List<SnapshotVuln>> result, Set<String> unresolvedKeys) throws IOException {
        processVulnEntry(content, ecosystem, namesWanted, result, unresolvedKeys, indexNames(ecosystem, namesWanted));
    }

    private static Map<String, List<String>> indexNames(String ecosystem, Map<String, Set<String>> wanted) {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        wanted.keySet().forEach(name -> aliases.computeIfAbsent(AdvisoryPackageNames.canonical(ecosystem, name),
                unused -> new ArrayList<>()).add(name));
        return aliases;
    }

    private void processVulnEntry(byte[] content, String ecosystem, Map<String, Set<String>> namesWanted,
                                  Map<String, List<SnapshotVuln>> result, Set<String> unresolvedKeys,
                                  Map<String, List<String>> aliases) throws IOException {
        processOriginal(readOriginal(content), ecosystem, namesWanted, result, unresolvedKeys, aliases);
    }

    private JsonNode readOriginal(byte[] content) throws IOException {
        JsonNode vuln;
        try {
            vuln = mapper.readTree(content);
        } catch (Exception e) {
            throw new IOException("Malformed OSV advisory JSON; source coverage cannot be established", e);
        }
        if (vuln == null || !vuln.isObject() || !vuln.path("id").isTextual() || vuln.path("id").asText().isBlank()) {
            throw new IOException("OSV advisory has no valid identity; source coverage cannot be established");
        }
        if (!vuln.path("modified").isTextual() || !OsvRevision.isCurrent(vuln.path("modified").asText())) {
            throw new IOException("OSV advisory revision is missing, malformed or in the future; source coverage is unknown");
        }
        return vuln;
    }

    private void processOriginal(JsonNode vuln, String ecosystem, Map<String, Set<String>> namesWanted,
                                 Map<String, List<SnapshotVuln>> result, Set<String> unresolvedKeys,
                                 Map<String, List<String>> aliases) throws IOException {
        OsvWithdrawal withdrawal = OsvWithdrawal.from(vuln);
        if (withdrawal == OsvWithdrawal.WITHDRAWN) return;
        if (withdrawal == OsvWithdrawal.UNKNOWN) {
            namesWanted.forEach((name, versions) -> versions.forEach(version -> {
                String key = AirgappedSnapshotService.componentKey(ecosystem, name, version);
                if (key != null) unresolvedKeys.add(key);
            }));
            return;
        }
        JsonNode affectedList = vuln.path("affected");
        if (!affectedList.isArray()) throw new IOException("OSV advisory has no affected array; source coverage is unknown");
        Set<String> affectedByThisAdvisory = new LinkedHashSet<>();
        Set<String> unresolvedByThisAdvisory = new LinkedHashSet<>();
        for (JsonNode affected : affectedList) {
            JsonNode pkg = affected.path("package");
            String pkgEcosystem = pkg.path("ecosystem").asText(null);
            String pkgName = pkg.path("name").asText(null);
            if (!pkg.path("name").isTextual() || !pkg.path("ecosystem").isTextual()
                    || pkgName.isBlank() || pkgEcosystem.isBlank()) {
                throw new IOException("OSV affected entry has no valid package identity; source coverage is unknown");
            }
            if (!ecosystem.equals(AirgappedSnapshotService.normalizeEcosystem(pkgEcosystem))) continue;
            for (String wantedName : aliases.getOrDefault(AdvisoryPackageNames.canonical(ecosystem, pkgName), List.of())) {
                Set<String> versionsWanted = namesWanted.get(wantedName);
                if (versionsWanted == null || versionsWanted.isEmpty()) continue;

                JsonNode enumeratedVersions = affected.path("versions");
                Set<String> enumerated = null;
                if (!enumeratedVersions.isMissingNode() && !enumeratedVersions.isArray()) {
                    throw new IOException("OSV versions is not an array; source coverage is unknown");
                }
                if (enumeratedVersions.isArray() && enumeratedVersions.size() > 0) {
                    enumerated = new LinkedHashSet<>();
                    for (JsonNode v : enumeratedVersions) {
                        if (!v.isTextual() || v.asText().isBlank()) {
                            throw new IOException("OSV versions contains an invalid version; source coverage is unknown");
                        }
                        enumerated.add(v.asText());
                    }
                }

                for (String wantedVersion : versionsWanted) {
                    String key = AirgappedSnapshotService.componentKey(ecosystem, wantedName, wantedVersion);
                    if (key == null) continue;
                    Boolean affectedResult = resolveAffected(ecosystem, wantedVersion, enumerated, affected.path("ranges"));
                    if (affectedResult == null) {
                        unresolvedByThisAdvisory.add(key);
                    } else if (affectedResult) {
                        if (affectedByThisAdvisory.add(key)) {
                            result.computeIfAbsent(key, k -> new ArrayList<>()).add(toSnapshotVuln(vuln, pkgEcosystem, pkgName, wantedVersion));
                        }
                    }
                }
            }
        }
        // Matching entries are a union within this advisory. Do not erase uncertainty
        // already recorded for the same component by another advisory.
        unresolvedByThisAdvisory.removeAll(affectedByThisAdvisory);
        unresolvedKeys.addAll(unresolvedByThisAdvisory);
    }

    /** {@code true} affected, {@code false} confidently not affected, {@code null} unresolved. */
    private static Boolean resolveAffected(String ecosystem, String version, Set<String> enumerated, JsonNode ranges) {
        return switch (OsvRangeEvaluator.evaluate(ecosystem, version, enumerated, ranges)) {
            case AFFECTED -> true;
            case NOT_AFFECTED -> false;
            case UNKNOWN -> null;
        };
    }

    private static SnapshotVuln toSnapshotVuln(JsonNode vuln, String ecosystem, String name, String version) {
        String osvId = vuln.path("id").asText(null);
        String summary = vuln.path("summary").asText(null);
        String cveId = null;
        for (JsonNode alias : vuln.path("aliases")) {
            String a = alias.asText("");
            if (a.startsWith("CVE-")) { cveId = a; break; }
        }
        String fixVersion = OsvFixVersionSelector.select(vuln, ecosystem, name, version).version();
        String cweId = null;
        JsonNode cweIds = vuln.path("database_specific").path("cwe_ids");
        if (cweIds.isArray() && !cweIds.isEmpty()) {
            String raw = cweIds.get(0).asText("").strip();
            if (!raw.isBlank()) {
                cweId = raw.startsWith("CWE-") ? raw : raw.matches("\\d+") ? "CWE-" + raw : raw;
            }
        }
        return new SnapshotVuln(osvId, cveId, summary, fixVersion, cweId);
    }
}
