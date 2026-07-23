package com.salkcoding.oswl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.entity.SnapshotEntry;
import com.salkcoding.oswl.domain.entity.SnapshotMeta;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.LibraryRepository;
import com.salkcoding.oswl.repository.SnapshotEntryRepository;
import com.salkcoding.oswl.repository.SnapshotMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Air-gapped (offline snapshot) store: import/export of vulnerability/threat-intel
 * snapshot bundles and the lookup API used by the offline client fallbacks
 * ({@code OsvClient}, {@code DepsDevClient}, {@code EpssClient}, {@code KevCatalogService}).
 *
 * A bundle is a zip of JSONL files, one per source:
 * <ul>
 *   <li>{@code osv.jsonl} — one line per component:
 *       {@code {"ecosystem":"Maven","name":"g:a","version":"1.0","vulns":[{"osvId":"GHSA-..","cveId":"CVE-..","summary":"..","fixVersion":"..","cweId":"CWE-.."}]}}</li>
 *   <li>{@code depsdev.jsonl} — version lines
 *       {@code {"type":"version","ecosystem":"MAVEN","name":"g:a","version":"1.0","licenses":["Apache-2.0"],"advisoryKeys":["GHSA-.."],"isDefault":false,"deprecated":null,"latestVersion":"1.1","scorecardScore":7.5}}
 *       and advisory lines
 *       {@code {"type":"advisory","ghsaId":"GHSA-..","title":"..","aliases":["CVE-.."],"cvss3Score":9.8,"cvss3Vector":"CVSS:3.1/.."}}</li>
 *   <li>{@code epss.jsonl} — {@code {"cveId":"CVE-..","score":0.42}}</li>
 *   <li>{@code kev.jsonl} — {@code {"cveId":"CVE-.."}}</li>
 * </ul>
 * Export mirrors the data the (online) instance has already fetched into its
 * Library/CVE tables; components absent from the snapshot resolve as "no data".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AirgappedSnapshotService {

    public static final String SOURCE_OSV = "osv";
    public static final String SOURCE_DEPSDEV_VERSION = "depsdev-version";
    public static final String SOURCE_DEPSDEV_ADVISORY = "depsdev-advisory";
    public static final String SOURCE_EPSS = "epss";
    public static final String SOURCE_KEV = "kev";
    public static final List<String> SOURCES = List.of(
            SOURCE_OSV, SOURCE_DEPSDEV_VERSION, SOURCE_DEPSDEV_ADVISORY, SOURCE_EPSS, SOURCE_KEV);

    private static final int SAVE_CHUNK_SIZE = 500;

    private final SnapshotEntryRepository snapshotEntryRepository;
    private final SnapshotMetaRepository snapshotMetaRepository;
    private final LibraryRepository libraryRepository;
    /** Local instance (codebase convention — matches DepsDevClient/GitHubService); avoids a bean dependency. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── DTO ──────────────────────────────────────────────────────────────

    /** OSV vulnerability entry (mirrors {@code OsvClient.OsvVuln}). */
    public record SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId) {}

    /** deps.dev GetVersion result (mirrors {@code DepsDevClient.VersionInfo}, always resolved). */
    public record SnapshotVersion(List<String> licenses, List<String> advisoryKeys, boolean isDefault,
                                  String deprecated, String latestVersion, Double scorecardScore) {}

    /** deps.dev GetAdvisory result (mirrors {@code DepsDevClient.AdvisoryInfo}). */
    public record SnapshotAdvisory(String ghsaId, String title, List<String> aliases,
                                   Double cvss3Score, String cvss3Vector) {}

    /** Per-source store status for the admin API. */
    public record SourceStatus(String source, long recordCount, LocalDateTime importedAt) {}

    /** Import outcome: record count per source. */
    public record SnapshotImportResult(Map<String, Integer> sources, int totalRecords) {}

    // ── Key handling ─────────────────────────────────────────────────────

    /**
     * Canonical component key shared by export, import, and offline lookups:
     * {@code ECOSYSTEM|name|version} with the ecosystem normalized to the deps.dev
     * uppercase form (OSV-style values like "Maven"/"npm"/"crates.io" map to the same form).
     * Returns null when any part is blank — such components are never resolvable offline
     * (the live clients skip them too).
     */
    public static String componentKey(String ecosystem, String name, String version) {
        if (isBlank(ecosystem) || isBlank(name) || isBlank(version)) {
            return null;
        }
        return normalizeEcosystem(ecosystem) + "|" + name.strip() + "|" + version.strip();
    }

    private static String normalizeEcosystem(String ecosystem) {
        return switch (ecosystem.strip().toLowerCase(Locale.ROOT)) {
            case "maven"             -> "MAVEN";
            case "npm"               -> "NPM";
            case "pypi"              -> "PYPI";
            case "go"                -> "GO";
            case "crates.io", "cargo" -> "CARGO";
            case "nuget"             -> "NUGET";
            case "rubygems"          -> "RUBYGEMS";
            default                  -> ecosystem.strip().toUpperCase(Locale.ROOT);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── Offline lookups (used by the client fallbacks) ───────────────────

    /** OSV vulns per component key; absent keys mean "no known vulnerabilities". */
    @Transactional(readOnly = true)
    public Map<String, List<SnapshotVuln>> findOsvVulns(Collection<String> componentKeys) {
        Map<String, List<SnapshotVuln>> result = new LinkedHashMap<>();
        findPayloads(SOURCE_OSV, componentKeys).forEach((key, payload) -> {
            try {
                result.put(key, objectMapper.readValue(payload, new TypeReference<>() {}));
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping corrupt osv entry key={}: {}", key, e.getMessage());
            }
        });
        return result;
    }

    /** deps.dev version info per component key; absent keys mean "unresolved". */
    @Transactional(readOnly = true)
    public Map<String, SnapshotVersion> findVersions(Collection<String> componentKeys) {
        return parsePayloads(SOURCE_DEPSDEV_VERSION, componentKeys, SnapshotVersion.class);
    }

    /** deps.dev advisories keyed by GHSA id; absent ids mean "lookup failed" (null result). */
    @Transactional(readOnly = true)
    public Map<String, SnapshotAdvisory> findAdvisories(Collection<String> ghsaIds) {
        return parsePayloads(SOURCE_DEPSDEV_ADVISORY, ghsaIds, SnapshotAdvisory.class);
    }

    /** EPSS scores keyed by uppercase CVE id; absent ids are simply omitted (live API semantics). */
    @Transactional(readOnly = true)
    public Map<String, Double> findEpssScores(Collection<String> cveIds) {
        if (cveIds == null || cveIds.isEmpty()) return Map.of();
        Set<String> keys = cveIds.stream()
                .filter(id -> id != null && id.startsWith("CVE-"))
                .map(id -> id.strip().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Double> result = new LinkedHashMap<>();
        findPayloads(SOURCE_EPSS, keys).forEach((key, payload) -> {
            try {
                result.put(key, Double.parseDouble(payload.trim()));
            } catch (NumberFormatException e) {
                log.warn("[Snapshot] Skipping corrupt epss entry key={}", key);
            }
        });
        return result;
    }

    /** All KEV-listed CVE ids in the store (uppercase). */
    @Transactional(readOnly = true)
    public Set<String> loadKevCveIds() {
        return snapshotEntryRepository.findEntryKeysBySource(SOURCE_KEV);
    }

    /** Per-source status for the admin API (sources never imported report 0 records). */
    @Transactional(readOnly = true)
    public List<SourceStatus> status() {
        Map<String, SnapshotMeta> meta = snapshotMetaRepository.findAll().stream()
                .collect(Collectors.toMap(SnapshotMeta::getSource, Function.identity()));
        List<SourceStatus> result = new ArrayList<>(SOURCES.size());
        for (String source : SOURCES) {
            SnapshotMeta m = meta.get(source);
            result.add(new SourceStatus(source,
                    m != null ? m.getRecordCount() : 0,
                    m != null ? m.getImportedAt() : null));
        }
        return result;
    }

    private Map<String, String> findPayloads(String source, Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return Map.of();
        return snapshotEntryRepository.findBySourceAndEntryKeyIn(source, keys).stream()
                .collect(Collectors.toMap(SnapshotEntry::getEntryKey, SnapshotEntry::getPayload, (a, _) -> a));
    }

    private <T> Map<String, T> parsePayloads(String source, Collection<String> keys, Class<T> type) {
        Map<String, T> result = new LinkedHashMap<>();
        findPayloads(source, keys).forEach((key, payload) -> {
            try {
                result.put(key, objectMapper.readValue(payload, type));
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping corrupt {} entry key={}: {}", source, key, e.getMessage());
            }
        });
        return result;
    }

    // ── Import ───────────────────────────────────────────────────────────

    /**
     * Replaces the store contents for every source present in the bundle.
     * Unknown zip entries (including {@code meta.json}) are ignored; malformed lines
     * are skipped with a warning. Throws {@link InvalidRequestException} when the
     * upload is not a zip or contains no known data files.
     */
    @Transactional
    public SnapshotImportResult importBundle(byte[] zipBytes) {
        Map<String, List<SnapshotEntry>> pending = new LinkedHashMap<>();
        int dataFiles = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                List<String> lines = null;
                switch (name) {
                    case "osv.jsonl"      -> lines = readLines(zis);
                    case "depsdev.jsonl"  -> lines = readLines(zis);
                    case "epss.jsonl"     -> lines = readLines(zis);
                    case "kev.jsonl"      -> lines = readLines(zis);
                    default -> { /* ignore meta.json and unknown entries */ }
                }
                if (lines == null) continue;
                dataFiles++;
                switch (name) {
                    case "osv.jsonl"     -> parseOsv(lines, pending);
                    case "depsdev.jsonl" -> parseDepsDev(lines, pending);
                    case "epss.jsonl"    -> parseEpss(lines, pending);
                    case "kev.jsonl"     -> parseKev(lines, pending);
                    default -> { /* unreachable */ }
                }
            }
        } catch (IOException e) {
            throw new InvalidRequestException("Snapshot bundle is not a readable zip: " + e.getMessage());
        }
        if (dataFiles == 0) {
            throw new InvalidRequestException(
                    "Snapshot bundle contains no data files (expected osv.jsonl, depsdev.jsonl, epss.jsonl or kev.jsonl)");
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, List<SnapshotEntry>> e : pending.entrySet()) {
            snapshotEntryRepository.deleteBySource(e.getKey());
            List<SnapshotEntry> entries = e.getValue();
            for (int i = 0; i < entries.size(); i += SAVE_CHUNK_SIZE) {
                snapshotEntryRepository.saveAll(entries.subList(i, Math.min(i + SAVE_CHUNK_SIZE, entries.size())));
            }
            snapshotMetaRepository.save(SnapshotMeta.builder()
                    .source(e.getKey())
                    .recordCount(entries.size())
                    .importedAt(now)
                    .build());
            counts.put(e.getKey(), entries.size());
            total += entries.size();
        }
        log.info("[Snapshot] Imported offline snapshot: {} records across {}", total, counts);
        return new SnapshotImportResult(counts, total);
    }

    private void parseOsv(List<String> lines, Map<String, List<SnapshotEntry>> pending) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String key = componentKey(text(node, "ecosystem"), text(node, "name"), text(node, "version"));
                if (key == null) {
                    log.warn("[Snapshot] Skipping osv line with missing ecosystem/name/version");
                    continue;
                }
                List<SnapshotVuln> vulns = new ArrayList<>();
                for (JsonNode v : node.path("vulns")) {
                    if (!v.isObject()) continue;
                    vulns.add(new SnapshotVuln(text(v, "osvId"), text(v, "cveId"),
                            text(v, "summary"), text(v, "fixVersion"), text(v, "cweId")));
                }
                addEntry(pending, SOURCE_OSV, key, objectMapper.writeValueAsString(vulns));
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping malformed osv line: {}", e.getMessage());
            }
        }
    }

    private void parseDepsDev(List<String> lines, Map<String, List<SnapshotEntry>> pending) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String type = text(node, "type");
                if ("version".equals(type)) {
                    String key = componentKey(text(node, "ecosystem"), text(node, "name"), text(node, "version"));
                    if (key == null) {
                        log.warn("[Snapshot] Skipping depsdev version line with missing ecosystem/name/version");
                        continue;
                    }
                    SnapshotVersion version = new SnapshotVersion(
                            stringList(node.path("licenses")), stringList(node.path("advisoryKeys")),
                            node.path("isDefault").asBoolean(false),
                            text(node, "deprecated"), text(node, "latestVersion"),
                            number(node, "scorecardScore"));
                    addEntry(pending, SOURCE_DEPSDEV_VERSION, key, objectMapper.writeValueAsString(version));
                } else if ("advisory".equals(type)) {
                    String ghsaId = text(node, "ghsaId");
                    if (isBlank(ghsaId)) {
                        log.warn("[Snapshot] Skipping depsdev advisory line with missing ghsaId");
                        continue;
                    }
                    SnapshotAdvisory advisory = new SnapshotAdvisory(ghsaId.strip(),
                            text(node, "title"), stringList(node.path("aliases")),
                            number(node, "cvss3Score"), text(node, "cvss3Vector"));
                    addEntry(pending, SOURCE_DEPSDEV_ADVISORY, ghsaId.strip(), objectMapper.writeValueAsString(advisory));
                } else {
                    log.warn("[Snapshot] Skipping depsdev line with unknown type={}", type);
                }
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping malformed depsdev line: {}", e.getMessage());
            }
        }
    }

    private void parseEpss(List<String> lines, Map<String, List<SnapshotEntry>> pending) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String cveId = text(node, "cveId");
                JsonNode score = node.path("score");
                if (isBlank(cveId) || !score.isNumber()) {
                    log.warn("[Snapshot] Skipping epss line with missing cveId/score");
                    continue;
                }
                addEntry(pending, SOURCE_EPSS,
                        cveId.strip().toUpperCase(Locale.ROOT),
                        Double.toString(score.asDouble()));
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping malformed epss line: {}", e.getMessage());
            }
        }
    }

    private void parseKev(List<String> lines, Map<String, List<SnapshotEntry>> pending) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = objectMapper.readTree(line);
                String cveId = text(node, "cveId");
                if (isBlank(cveId)) {
                    log.warn("[Snapshot] Skipping kev line with missing cveId");
                    continue;
                }
                addEntry(pending, SOURCE_KEV, cveId.strip().toUpperCase(Locale.ROOT), "1");
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping malformed kev line: {}", e.getMessage());
            }
        }
    }

    /** Reads one zip entry fully as UTF-8 lines (leaves the stream open for the next entry). */
    private static List<String> readLines(ZipInputStream zis) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private void addEntry(Map<String, List<SnapshotEntry>> pending, String source, String key, String payload) {
        pending.computeIfAbsent(source, _ -> new ArrayList<>())
                .add(SnapshotEntry.builder().source(source).entryKey(key).payload(payload).build());
    }

    // ── Export ───────────────────────────────────────────────────────────

    /**
     * Builds a snapshot bundle from the data this instance has already fetched
     * (libraries + CVEs). Run on an ONLINE instance, then import on the air-gapped one.
     * Only deps.dev-resolved libraries ({@code isLatestVersion != null}) contribute
     * version records, and only CVEs carrying advisory data contribute advisory records,
     * so the snapshot mirrors live-API fidelity.
     */
    @Transactional(readOnly = true)
    public byte[] exportBundle() {
        List<Library> libraries = libraryRepository.findAll();

        StringBuilder osv = new StringBuilder();
        StringBuilder depsdev = new StringBuilder();
        Map<String, Double> epss = new LinkedHashMap<>();
        Set<String> kev = new LinkedHashSet<>();
        Map<String, SnapshotAdvisory> advisories = new LinkedHashMap<>();
        int versionRecords = 0;

        for (Library lib : libraries) {
            String key = componentKey(lib.getEcosystem(), lib.getName(), lib.getVersion());
            if (key == null) continue;
            List<Cve> cves = lib.getCves();

            // osv.jsonl — only libraries with known vulns; absent key = "no vulnerabilities" offline
            if (!cves.isEmpty()) {
                List<SnapshotVuln> vulns = cves.stream()
                        .map(c -> new SnapshotVuln(c.getGhsaId(), c.getCveId(), c.getSummary(),
                                c.getFixVersion(), c.getCweId()))
                        .toList();
                ObjectNode line = objectMapper.createObjectNode();
                line.put("ecosystem", lib.getEcosystem());
                line.put("name", lib.getName());
                line.put("version", lib.getVersion());
                line.set("vulns", objectMapper.valueToTree(vulns));
                osv.append(writeJson(line)).append('\n');
            }

            // depsdev.jsonl version record — only when deps.dev GetVersion resolved
            List<String> advisoryKeys = cves.stream()
                    .filter(c -> c.getGhsaId() != null && c.getGhsaId().startsWith("GHSA-") && hasAdvisoryData(c))
                    .map(Cve::getGhsaId)
                    .distinct()
                    .toList();
            if (lib.getIsLatestVersion() != null) {
                SnapshotVersion version = new SnapshotVersion(
                        isBlank(lib.getLicenseName()) ? List.of() : List.of(lib.getLicenseName()),
                        advisoryKeys,
                        lib.getIsLatestVersion(),
                        lib.getDeprecated(),
                        lib.getLatestVersion(),
                        lib.getScorecardScore());
                ObjectNode line = objectMapper.valueToTree(version);
                line.put("type", "version");
                line.put("ecosystem", lib.getEcosystem());
                line.put("name", lib.getName());
                line.put("version", lib.getVersion());
                depsdev.append(writeJson(line)).append('\n');
                versionRecords++;
            }

            for (Cve c : cves) {
                // depsdev.jsonl advisory records (deduped — CVEs are per-library)
                String ghsaId = c.getGhsaId();
                if (ghsaId != null && ghsaId.startsWith("GHSA-") && hasAdvisoryData(c)
                        && !advisories.containsKey(ghsaId)) {
                    advisories.put(ghsaId, new SnapshotAdvisory(ghsaId, c.getTitle(),
                            c.getCveId() != null ? List.of(c.getCveId()) : List.of(),
                            c.getCvssScore(), c.getCvss3Vector()));
                }
                // epss.jsonl / kev.jsonl (deduped by CVE id)
                if (c.getCveId() != null) {
                    String cveId = c.getCveId().strip().toUpperCase(Locale.ROOT);
                    if (c.getEpssScore() != null) epss.putIfAbsent(cveId, c.getEpssScore());
                    if (Boolean.TRUE.equals(c.getKevListed())) kev.add(cveId);
                }
            }
        }

        for (SnapshotAdvisory advisory : advisories.values()) {
            ObjectNode line = objectMapper.valueToTree(advisory);
            line.put("type", "advisory");
            depsdev.append(writeJson(line)).append('\n');
        }
        StringBuilder epssLines = new StringBuilder();
        epss.forEach((cveId, score) -> {
            ObjectNode line = objectMapper.createObjectNode();
            line.put("cveId", cveId);
            line.put("score", score);
            epssLines.append(writeJson(line)).append('\n');
        });
        StringBuilder kevLines = new StringBuilder();
        for (String cveId : kev) {
            kevLines.append("{\"cveId\":\"").append(cveId).append("\"}\n");
        }

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("format", "oswl-airgap-snapshot");
        meta.put("version", 1);
        meta.put("exportedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("libraries", libraries.size());
        meta.put("versionRecords", versionRecords);
        meta.put("advisoryRecords", advisories.size());
        meta.put("epssRecords", epss.size());
        meta.put("kevRecords", kev.size());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
                writeZipEntry(zos, "meta.json", writeJson(meta));
                writeZipEntry(zos, "osv.jsonl", osv.toString());
                writeZipEntry(zos, "depsdev.jsonl", depsdev.toString());
                writeZipEntry(zos, "epss.jsonl", epssLines.toString());
                writeZipEntry(zos, "kev.jsonl", kevLines.toString());
            }
            log.info("[Snapshot] Exported offline snapshot: {} libraries, {} version records, {} advisories, {} epss, {} kev",
                    libraries.size(), versionRecords, advisories.size(), epss.size(), kev.size());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build snapshot bundle: " + e.getMessage(), e);
        }
    }

    /** True when the CVE row carries deps.dev advisory data (vs. an OSV-only finding). */
    private boolean hasAdvisoryData(Cve c) {
        return c.getTitle() != null || c.getCvssScore() != null || c.getCvss3Vector() != null;
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize snapshot line: " + e.getMessage(), e);
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ── JSON helpers ─────────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static Double number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    private static List<String> stringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode v : node) {
            if (v.isTextual()) result.add(v.asText());
        }
        return result;
    }
}
