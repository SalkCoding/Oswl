package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;

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
 * from vuln-unit to component-unit against a wanted-list (E5.3/E6 — full re-indexing without a
 * wanted-list is a combinatorial explosion the plan explicitly rules out).
 *
 * <p>Coverage: an {@code affected[]} entry is resolved via its enumerated {@code versions[]} list
 * when present (exact match — always reliable), otherwise via a best-effort SEMVER range check
 * ({@link SimpleVersionComparator}) which can fail to parse exotic version strings. Anything
 * neither path can confidently resolve is <b>never</b> silently treated as "not affected" — it's
 * counted in {@link Result#unresolvedComponentCount()} and surfaced in the bundle's
 * {@code meta.json} coverage stats, per the plan's explicit requirement that "no data" and
 * "confirmed clean" must stay distinguishable.
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
            if (!ECOSYSTEM_TO_BUCKET.containsKey(eco)) continue;
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
            String bucket = ECOSYSTEM_TO_BUCKET.get(ecosystem);
            Map<String, Set<String>> namesWanted = ecoEntry.getValue();
            System.err.println("[oswl-vdb] OSV: fetching " + bucket + "/all.zip for " + namesWanted.size() + " wanted package name(s)");
            byte[] zipBytes = cache.getOrFetch("osv-" + bucket.replace('/', '_') + "-all.zip",
                    "https://storage.googleapis.com/osv-vulnerabilities/" + bucket + "/all.zip");
            asOfByBucket.put(ecosystem, LocalDate.now());

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
                Boolean affectedResult = resolveAffected(wantedVersion, enumerated, affected.path("ranges"));
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
    private static Boolean resolveAffected(String version, Set<String> enumerated, JsonNode ranges) {
        if (enumerated != null) {
            return enumerated.contains(version);
        }
        if (!ranges.isArray() || ranges.isEmpty()) {
            return false; // no versions[] and no ranges[] at all -> nothing says this version is affected
        }
        boolean anyUnresolved = false;
        for (JsonNode range : ranges) {
            if (!"SEMVER".equals(range.path("type").asText(null))
                    && !"GIT".equals(range.path("type").asText(null))) {
                anyUnresolved = true; // ECOSYSTEM-typed ranges (Debian/Alpine style) — not applicable/comparable here
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
                    if (SimpleVersionComparator.inRange(version, introduced, null)) return true;
                } else {
                    for (String fixed : fixedBoundaries) {
                        if (SimpleVersionComparator.inRange(version, introduced, fixed)) return true;
                    }
                }
            } catch (IllegalArgumentException e) {
                anyUnresolved = true;
            }
        }
        return anyUnresolved ? null : false;
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
