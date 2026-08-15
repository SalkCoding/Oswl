package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService;

import java.io.ByteArrayInputStream;
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
 * <p>Coverage: an {@code affected[]} entry is resolved via its enumerated {@code versions[]} list
 * when present (exact match — always reliable), otherwise via a best-effort SEMVER range check
 * ({@link SimpleVersionComparator}) which can fail to parse exotic version strings. Anything
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
            // matters most. Falls back to "today" only if the server didn't send the header, with
            // a visible warning rather than a silent optimistic guess.
            LocalDate asOf = fetched.lastModified();
            if (asOf == null) {
                System.err.println("[oswl-vdb] WARNING: " + bucket + "/all.zip had no Last-Modified header — "
                        + "assuming today's date for its freshness, which may overstate how current this data is");
                asOf = LocalDate.now();
            }
            asOfByBucket.put(ecosystem, asOf);

            int entriesScanned = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".json")) continue;
                    entriesScanned++;
                    if (entriesScanned % 20000 == 0) {
                        System.err.println("[oswl-vdb] OSV " + bucket + ": scanned " + entriesScanned + " entries");
                    }
                    byte[] content = zis.readAllBytes();
                    processVulnEntry(content, ecosystem, namesWanted, result, unresolvedKeys);
                }
            }
            System.err.println("[oswl-vdb] OSV " + bucket + ": " + entriesScanned + " vuln entries scanned");
        }
        return new Result(result, unresolvedKeys, asOfByBucket);
    }

    private void processVulnEntry(byte[] content, String ecosystem, Map<String, Set<String>> namesWanted,
                                   Map<String, List<SnapshotVuln>> result, Set<String> unresolvedKeys) {
        JsonNode vuln;
        try {
            vuln = mapper.readTree(content);
        } catch (Exception e) {
            return; // malformed entry — skip, matches the ingest-side tolerance elsewhere in this codebase
        }
        JsonNode affectedList = vuln.path("affected");
        if (!affectedList.isArray()) return;
        boolean anyMatch = false;
        for (JsonNode affected : affectedList) {
            JsonNode pkg = affected.path("package");
            String pkgEcosystem = pkg.path("ecosystem").asText(null);
            String pkgName = pkg.path("name").asText(null);
            if (pkgName == null || pkgEcosystem == null) continue;
            if (!ecosystem.equals(AirgappedSnapshotService.normalizeEcosystem(pkgEcosystem))) continue;
            Set<String> versionsWanted = namesWanted.get(pkgName);
            if (versionsWanted == null || versionsWanted.isEmpty()) continue;

            JsonNode enumeratedVersions = affected.path("versions");
            Set<String> enumerated = null;
            if (enumeratedVersions.isArray() && enumeratedVersions.size() > 0) {
                enumerated = new LinkedHashSet<>();
                for (JsonNode v : enumeratedVersions) enumerated.add(v.asText());
            }

            for (String wantedVersion : versionsWanted) {
                String key = AirgappedSnapshotService.componentKey(ecosystem, pkgName, wantedVersion);
                if (key == null) continue;
                Boolean affectedResult = resolveAffected(ecosystem, wantedVersion, enumerated, affected.path("ranges"));
                if (affectedResult == null) {
                    unresolvedKeys.add(key);
                } else if (affectedResult) {
                    result.computeIfAbsent(key, k -> new ArrayList<>()).add(toSnapshotVuln(vuln));
                    anyMatch = true;
                }
            }
        }
        if (!anyMatch) {
            // still fine — most vulns don't touch any wanted component; nothing to record
        }
    }

    /** {@code true} affected, {@code false} confidently not affected, {@code null} unresolved. */
    private static Boolean resolveAffected(String ecosystem, String version, Set<String> enumerated, JsonNode ranges) {
        if (enumerated != null) {
            return enumerated.contains(version);
        }
        if (!ranges.isArray() || ranges.isEmpty()) {
            return false; // no versions[] and no ranges[] at all -> nothing says this version is affected
        }
        // Alpine's OSV advisories are ECOSYSTEM-typed ranges only (no enumerated versions[] and
        // apk's version scheme isn't SemVer/GIT) — everyone else's ECOSYSTEM-typed ranges
        // (Debian/Ubuntu style) stay unresolved here because they're already resolved via
        // enumerated versions[] before this method is ever reached for them.
        boolean isAlpine = ecosystem.startsWith("ALPINE:");
        boolean anyUnresolved = false;
        for (JsonNode range : ranges) {
            String rangeType = range.path("type").asText(null);
            boolean semverOrGit = "SEMVER".equals(rangeType) || "GIT".equals(rangeType);
            boolean apkEcosystemRange = isAlpine && "ECOSYSTEM".equals(rangeType);
            if (!semverOrGit && !apkEcosystemRange) {
                anyUnresolved = true; // ECOSYSTEM-typed ranges for non-Alpine ecosystems — not comparable here
                continue;
            }
            String introduced = null;
            List<String> fixedBoundaries = new ArrayList<>();
            for (JsonNode event : range.path("events")) {
                String i = event.path("introduced").asText(null);
                if (i != null) introduced = i.equals("0") ? null : i;
                String f = event.path("fixed").asText(null);
                if (f != null) fixedBoundaries.add(f);
                String la = event.path("last_affected").asText(null);
                if (la != null) fixedBoundaries.add(la); // treated as an inclusive upper bound below
            }
            try {
                if (fixedBoundaries.isEmpty()) {
                    if (inRange(apkEcosystemRange, version, introduced, null)) return true;
                } else {
                    for (String fixed : fixedBoundaries) {
                        if (inRange(apkEcosystemRange, version, introduced, fixed)) return true;
                    }
                }
            } catch (IllegalArgumentException e) {
                anyUnresolved = true;
            }
        }
        return anyUnresolved ? null : false;
    }

    private static boolean inRange(boolean apk, String version, String introduced, String fixed) {
        return apk
                ? ApkVersionComparator.inRange(version, introduced, fixed)
                : SimpleVersionComparator.inRange(version, introduced, fixed);
    }

    private static SnapshotVuln toSnapshotVuln(JsonNode vuln) {
        String osvId = vuln.path("id").asText(null);
        String summary = vuln.path("summary").asText(null);
        String cveId = null;
        for (JsonNode alias : vuln.path("aliases")) {
            String a = alias.asText("");
            if (a.startsWith("CVE-")) { cveId = a; break; }
        }
        String fixVersion = null;
        outer:
        for (JsonNode affected : vuln.path("affected")) {
            for (JsonNode range : affected.path("ranges")) {
                for (JsonNode event : range.path("events")) {
                    String fixed = event.path("fixed").asText(null);
                    if (fixed != null && !fixed.isBlank()) { fixVersion = fixed; break outer; }
                }
            }
        }
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
