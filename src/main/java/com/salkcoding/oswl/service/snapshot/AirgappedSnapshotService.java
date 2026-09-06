package com.salkcoding.oswl.service.snapshot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta;
import com.salkcoding.oswl.domain.enums.CveSource;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotEntryRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Air-gapped (offline snapshot) store: import/export of vulnerability/threat-intel
 * snapshot bundles and the lookup API used by the offline client fallbacks
 * ({@code OsvClient}, {@code DepsDevClient}, {@code EpssClient}, {@code KevCatalogService}).
 *
 * <p>A bundle is a zip of JSONL files, one per source, plus a {@code meta.json} describing
 * provenance. {@code meta.json}'s {@code formatVersion}:
 * <ul>
 *   <li>{@code 2} (current) — {@code bundleId}/{@code mode}/{@code builtAt}/{@code sources[].asOf}/
 *       {@code sources[].origin}/{@code files[].sha256} are read and enforced (checksum mismatch
 *       rejects the whole bundle before any store mutation).</li>
 *   <li>missing or {@code 1} — legacy bundle: {@code meta.json} content (if present at all) is
 * ignored beyond format detection, exactly like the original behavior, so old exports keep
 *       importing.</li>
 * </ul>
 *
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
 * A line in any file may carry {@code "_deleted":true} — in {@link ImportMode#MERGE} this
 * removes the key instead of upserting it; in {@link ImportMode#REPLACE} it is simply skipped
 * (REPLACE already clears the source first, so there is nothing to delete).
 *
 * <p>Export mirrors the data the (online) instance has already fetched into its Library/CVE
 * tables; components absent from the snapshot resolve as "no data". This makes the export
 * strictly narrower than a bundle built directly from upstream (building directly from upstream
 * is not yet implemented in this codebase) — {@code meta.json}'s {@code origin} is set to
 * {@code "derived-from-scan"} for every source to disclose that limitation, most importantly for
 * advisory {@code aliases} (this entity only ever stores one, {@code Cve.cveId}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AirgappedSnapshotService {

    public static final String SOURCE_OSV = "osv";
    public static final String SOURCE_DEPSDEV_VERSION = "depsdev-version";
    public static final String SOURCE_DEPSDEV_ADVISORY = "depsdev-advisory";
    public static final String SOURCE_GITHUB_ADVISORY = "github-advisory";
    public static final String SOURCE_NVD = "nvd";
    public static final String SOURCE_EPSS = "epss";
    public static final String SOURCE_KEV = "kev";
    /** Components a wanted-list included but the {@code oswl-vdb} builder never resolved
     * (upstream had no data, or a range couldn't be confidently evaluated) — never populated by
     * this app's own {@link #exportBundle()}, only by bundles built via the CLI. Kept as an
     * ordinary source (not folded into {@code osv}) so the existing per-source status/import-count
     * plumbing surfaces it without any bespoke wiring. */
    public static final String SOURCE_UNRESOLVED = "unresolved";
    public static final List<String> SOURCES = List.of(
            SOURCE_OSV, SOURCE_DEPSDEV_VERSION, SOURCE_DEPSDEV_ADVISORY, SOURCE_GITHUB_ADVISORY,
            SOURCE_NVD, SOURCE_EPSS, SOURCE_KEV, SOURCE_UNRESOLVED);

    private static final String BUNDLE_FORMAT = "oswl-vdb";
    private static final int CURRENT_FORMAT_VERSION = 2;
    private static final String EXPORT_ORIGIN = "derived-from-scan";

    private static final int SAVE_CHUNK_SIZE = 500;

    private static final Set<String> KNOWN_DATA_FILES =
            Set.of("osv.jsonl", "depsdev.jsonl", "github-advisory.jsonl", "nvd.jsonl",
                    "epss.jsonl", "kev.jsonl", "unresolved.jsonl");

    private final SnapshotEntryRepository snapshotEntryRepository;
    private final SnapshotMetaRepository snapshotMetaRepository;
    private final LibraryRepository libraryRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    /** Local instance (codebase convention — matches DepsDevClient/GitHubService); avoids a bean dependency. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── DTO ──────────────────────────────────────────────────────────────

    /**
     * Vulnerability entry stored per component. Originally modelled on {@code OsvClient.OsvVuln},
     * now also carries severity/CVSS/confidence so it can represent GitHub Advisory and
     * NVD matches in the same offline JSON array.
     */
    public record SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId,
                                String severity, Double cvssScore, String cvss3Vector, String matchConfidence) {
        public SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId) {
            this(osvId, cveId, summary, fixVersion, cweId, null, null, null, null);
        }
    }

    /** deps.dev GetVersion result (mirrors {@code DepsDevClient.VersionInfo}, always resolved). */
    public record SnapshotVersion(List<String> licenses, List<String> advisoryKeys, boolean isDefault,
                                  String deprecated, String latestVersion, Double scorecardScore) {}

    /** deps.dev GetAdvisory result (mirrors {@code DepsDevClient.AdvisoryInfo}). */
    public record SnapshotAdvisory(String ghsaId, String title, List<String> aliases,
                                   Double cvss3Score, String cvss3Vector) {}

    /**
     * Per-source store status for the admin API. The provenance fields (everything after
     * {@code importedAt}) are null for a source last imported from a v1 (or meta-less) bundle —
     * new fields on an existing DTO is backward compatible (existing clients simply ignore
     * fields they don't know about).
     */
    public record SourceStatus(String source, long recordCount, LocalDateTime importedAt,
                               String bundleId, LocalDateTime builtAt, LocalDate sourceAsOf, String origin) {}

    /** Import outcome: record count per source. */
    public record SnapshotImportResult(Map<String, Integer> sources, int totalRecords, String mode) {}

    /** REPLACE clears each source before writing (original behavior); MERGE upserts by key. */
    public enum ImportMode { REPLACE, MERGE }

    /** Parsed {@code meta.json} (v2 only — v1/missing meta never reaches this type, see {@link #parseMetaV2}). */
    private record BundleSourceMeta(Integer records, LocalDate asOf, String origin) {}
    private record BundleFileMeta(String sha256, Integer lines) {}
    private record BundleMetaV2(String mode, LocalDateTime builtAt, String bundleId,
                                Map<String, BundleSourceMeta> sources, Map<String, BundleFileMeta> files) {}

    /** One decoded JSONL line: either a normal upsert ({@code deleted=false}) or a delete marker. */
    private record ParsedLine(String key, String payload, boolean deleted) {}

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

    /** Public so the {@code oswl-vdb} builder shares this exact normalization instead of a
     * second, drift-prone copy — a normalization mismatch between the builder and this class
     * makes a bundle import silently unresolvable. */
    public static String normalizeEcosystem(String ecosystem) {
        return switch (ecosystem.strip().toLowerCase(Locale.ROOT)) {
            case "maven"             -> "MAVEN";
            case "npm"               -> "NPM";
            case "pypi"              -> "PYPI";
            case "go"                -> "GO";
            case "crates.io", "cargo" -> "CARGO";
            case "nuget"             -> "NUGET";
            case "rubygems"          -> "RUBYGEMS";
            case "packagist", "composer"   -> "COMPOSER";
            case "conancenter", "conan"    -> "CONAN";
            default                  -> ecosystem.strip().toUpperCase(Locale.ROOT);
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ── Offline lookups (used by the client fallbacks) ───────────────────

    /** OSV vulns per component key; absent keys are unresolved, unlike a stored empty result. */
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

    /** GitHub Advisory vulnerabilities keyed by component key. */
    @Transactional(readOnly = true)
    public Map<String, List<SnapshotVuln>> findGitHubAdvisoryVulns(Collection<String> componentKeys) {
        return parseVulnLists(SOURCE_GITHUB_ADVISORY, componentKeys);
    }

    /** NVD CPE-matched vulnerabilities keyed by component key. */
    @Transactional(readOnly = true)
    public Map<String, List<SnapshotVuln>> findNvdVulns(Collection<String> componentKeys) {
        return parseVulnLists(SOURCE_NVD, componentKeys);
    }

    private Map<String, List<SnapshotVuln>> parseVulnLists(String source, Collection<String> keys) {
        Map<String, List<SnapshotVuln>> result = new LinkedHashMap<>();
        findPayloads(source, keys).forEach((key, payload) -> {
            try {
                result.put(key, objectMapper.readValue(payload, new TypeReference<>() {}));
            } catch (Exception e) {
                log.warn("[Snapshot] Skipping corrupt {} entry key={}: {}", source, key, e.getMessage());
            }
        });
        return result;
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
                    m != null ? m.getImportedAt() : null,
                    m != null ? m.getBundleId() : null,
                    m != null ? m.getBuiltAt() : null,
                    m != null ? m.getSourceAsOf() : null,
                    m != null ? m.getOrigin() : null));
        }
        return result;
    }

    /**
     * The oldest {@code sourceAsOf} across every source that has ever been imported — the
     * value staleness is measured against (never {@code builtAt}/{@code importedAt}, which say
     * when the bundle/import happened, not how fresh the upstream data itself is). Null when no
     * source has provenance yet (never imported, or only ever imported from a v1/meta-less bundle).
     */
    @Transactional(readOnly = true)
    public LocalDate oldestSourceAsOf() {
        return snapshotMetaRepository.findAll().stream()
                .map(SnapshotMeta::getSourceAsOf)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    /**
     * Streams every distinct (ecosystem, name, version) this instance has ever scanned, as
     * JSONL, for an offline site to hand to the {@code oswl-vdb} builder so it can fetch
     * exactly the components that matter instead of a full upstream mirror. Deliberately omits
     * project names, repository URLs, and paths — only ecosystem/name/version leave the instance, and those
     * are already what any upstream vulnerability API needs to look a component up.
     */
    @Transactional(readOnly = true)
    public void streamWantedList(java.io.OutputStream out) throws IOException {
        try (Stream<LibraryRepository.WantedComponentProjection> rows = libraryRepository.streamWantedComponents()) {
            java.io.Writer writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8));
            java.util.Iterator<LibraryRepository.WantedComponentProjection> it = rows.iterator();
            while (it.hasNext()) {
                LibraryRepository.WantedComponentProjection p = it.next();
                ObjectNode node = objectMapper.createObjectNode();
                node.put("ecosystem", p.getEcosystem());
                node.put("name", p.getName());
                node.put("version", p.getVersion());
                writer.write(objectMapper.writeValueAsString(node));
                writer.write("\n");
            }
            writer.flush();
        }
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
     * Legacy entry point — always REPLACE, regardless of what {@code meta.json} (if any) says.
     * Kept byte-for-byte behavior-compatible for existing callers/tests: REPLACE mode's behavior
     * is unchanged from before. New callers (the admin controller) should call the 2-arg
     * overload with an explicit mode, or {@code null} to let {@code meta.json} decide.
     */
    public SnapshotImportResult importBundle(InputStream zipStream) {
        return importBundle(zipStream, ImportMode.REPLACE);
    }

    /**
     * Takes the zip as a stream rather than a fully-buffered {@code byte[]} — the caller
     * (the admin controller) passes the multipart upload's own input stream directly, so the
     * compressed upload itself is never buffered whole in memory before this method even starts
     * (decompressed entries are staged to bounded temporary files by {@link SnapshotBundleStager}).
     *
     * @param requestedMode explicit mode, or {@code null} to resolve from {@code meta.json}'s
     *                      {@code mode} field (falling back to {@link ImportMode#REPLACE} when
     *                      that is also absent) — see the class javadoc mode-priority note.
     * @throws InvalidRequestException on an unreadable zip, no recognized data files, or (v2
     *         bundles only) a SHA-256 mismatch against {@code meta.json} — thrown before any
     *         store mutation, so a corrupt/tampered bundle never touches the existing store.
     */
    public SnapshotImportResult importBundle(InputStream zipStream, ImportMode requestedMode) {
        try (SnapshotBundleStager staged = new SnapshotBundleStager()) {
            staged.read(zipStream, KNOWN_DATA_FILES);
            Map<String, Path> rawFiles = new LinkedHashMap<>(staged.files());
            Path metaFile = rawFiles.remove("meta.json");
            if (rawFiles.isEmpty()) throw new InvalidRequestException("Snapshot bundle contains no recognized data files.");
            BundleMetaV2 meta = metaFile == null ? null : parseMetaV2(Files.readAllBytes(metaFile));
            if (meta != null) verifyChecksums(meta, rawFiles);
            // Validate line budgets before a REPLACE is allowed to delete existing source data.
            rawFiles.values().forEach(file -> SnapshotBundleStager.forEachLine(file, ignored -> {}));
            ImportMode mode = requestedMode != null ? requestedMode : resolveModeFromMeta(meta);
            SnapshotBundleStager.checkInterrupted();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(300);
            return transaction.execute(status -> applyBundle(rawFiles, meta, mode));
        } catch (IOException e) {
            throw new InvalidRequestException("Snapshot bundle is not a readable zip: " + e.getMessage());
        }
    }

    private SnapshotImportResult applyBundle(Map<String, Path> rawFiles, BundleMetaV2 meta, ImportMode mode) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Path> rf : rawFiles.entrySet()) {
            SnapshotBundleStager.checkInterrupted();
            switch (rf.getKey()) {
                case "osv.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_OSV, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestOsvLine(line, buf));
                    counts.merge(SOURCE_OSV, buf.finish(), Integer::sum);
                }
                case "depsdev.jsonl" -> {
                    SourceIngestBuffer versionBuf = new SourceIngestBuffer(SOURCE_DEPSDEV_VERSION, mode);
                    SourceIngestBuffer advisoryBuf = new SourceIngestBuffer(SOURCE_DEPSDEV_ADVISORY, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestDepsDevLine(line, versionBuf, advisoryBuf));
                    counts.merge(SOURCE_DEPSDEV_VERSION, versionBuf.finish(), Integer::sum);
                    counts.merge(SOURCE_DEPSDEV_ADVISORY, advisoryBuf.finish(), Integer::sum);
                }
                case "github-advisory.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_GITHUB_ADVISORY, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestOsvLine(line, buf));
                    counts.merge(SOURCE_GITHUB_ADVISORY, buf.finish(), Integer::sum);
                }
                case "nvd.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_NVD, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestOsvLine(line, buf));
                    counts.merge(SOURCE_NVD, buf.finish(), Integer::sum);
                }
                case "epss.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_EPSS, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestEpssLine(line, buf));
                    counts.merge(SOURCE_EPSS, buf.finish(), Integer::sum);
                }
                case "kev.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_KEV, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestKevLine(line, buf));
                    counts.merge(SOURCE_KEV, buf.finish(), Integer::sum);
                }
                case "unresolved.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_UNRESOLVED, mode);
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> ingestOsvLine(line, buf)); // same {ecosystem,name,version} shape, no "vulns" needed
                    counts.merge(SOURCE_UNRESOLVED, buf.finish(), Integer::sum);
                }
                default -> { /* unreachable — filtered by KNOWN_DATA_FILES above */ }
            }
        }

        // recordCount is always a fresh count query, never an accumulated delta — a MERGE
        // over existing keys must not double-count, and a REPLACE's true count is simply "what's
        // there now" regardless of how many lines the bundle had (duplicates within one bundle
        // collapse to one row via the (source, entry_key) unique constraint).
        int total = 0;
        LocalDateTime now = LocalDateTime.now();
        for (String source : counts.keySet()) {
            long actualCount = snapshotEntryRepository.countBySource(source);
            total += (int) actualCount;
            SnapshotMeta.SnapshotMetaBuilder builder = SnapshotMeta.builder()
                    .source(source)
                    .recordCount(actualCount)
                    .importedAt(now);
            if (meta != null) {
                BundleSourceMeta sourceMeta = meta.sources() != null ? meta.sources().get(source) : null;
                builder.bundleId(meta.bundleId())
                        .builtAt(meta.builtAt())
                        .sourceAsOf(sourceMeta != null ? sourceMeta.asOf() : null)
                        .origin(sourceMeta != null ? sourceMeta.origin() : null)
                        .formatVersion(CURRENT_FORMAT_VERSION);
            }
            snapshotMetaRepository.save(builder.build());
        }
        log.info("[Snapshot] Imported offline snapshot ({} mode): {} records across {}", mode, total, counts);
        return new SnapshotImportResult(counts, total, mode.name());
    }

    private static ImportMode resolveModeFromMeta(BundleMetaV2 meta) {
        if (meta != null && "delta".equalsIgnoreCase(meta.mode())) {
            return ImportMode.MERGE;
        }
        return ImportMode.REPLACE;
    }

    /**
     * Buffers one source's entries and flushes every {@link #SAVE_CHUNK_SIZE} rows instead
     * of accumulating the whole source (let alone every source at once, as the original code
     * did) in memory before the first write — the proximate cause of L6's OOM risk.
     */
    private final class SourceIngestBuffer {
        private final String source;
        private final ImportMode mode;
        private final List<SnapshotEntry> buffer = new ArrayList<>();
        private boolean replaceCleared = false;
        private int total = 0;

        SourceIngestBuffer(String source, ImportMode mode) {
            this.source = source;
            this.mode = mode;
        }

        void add(ParsedLine line) {
            if (line == null) return;
            if (line.deleted()) {
                if (mode == ImportMode.MERGE) {
                    flush(); // preserve line order: apply pending upserts before this delete
                    snapshotEntryRepository.deleteBySourceAndEntryKey(source, line.key());
                }
                // REPLACE already clears the whole source up front — a delete marker is a no-op there.
                return;
            }
            buffer.add(SnapshotEntry.builder().source(source).entryKey(line.key()).payload(line.payload()).build());
            total++;
            if (buffer.size() >= SAVE_CHUNK_SIZE) flush();
        }

        private void flush() {
            if (buffer.isEmpty()) return;
            if (mode == ImportMode.REPLACE && !replaceCleared) {
                snapshotEntryRepository.deleteBySource(source);
                replaceCleared = true;
            }
            if (mode == ImportMode.MERGE) {
                upsertChunk(source, buffer);
            } else {
                List<SnapshotEntry> saved = snapshotEntryRepository.saveAll(buffer);
                snapshotEntryRepository.flush();
                saved.forEach(entityManager::detach);
            }
            // chunk-level progress visibility for large imports — total accumulates across
            // flushes, so this traces how far a multi-minute import has gotten without a separate
            // job-status endpoint.
            log.debug("[Snapshot] {} mode={}: flushed chunk ({} rows so far)", source, mode, total);
            buffer.clear();
        }

        /** Final flush; returns how many non-delete lines this source saw (informational only — real counts come from a fresh count query). */
        int finish() {
            flush();
            if (mode == ImportMode.REPLACE && !replaceCleared) {
                // Bundle carried zero (or all-deleted) lines for this source — REPLACE still
                // means "the store now reflects the bundle", i.e. empty.
                snapshotEntryRepository.deleteBySource(source);
            }
            return total;
        }
    }

    private void upsertChunk(String source, List<SnapshotEntry> chunk) {
        Set<String> keys = chunk.stream().map(SnapshotEntry::getEntryKey).collect(Collectors.toSet());
        Map<String, SnapshotEntry> existing = snapshotEntryRepository.findBySourceAndEntryKeyIn(source, keys).stream()
                .collect(Collectors.toMap(SnapshotEntry::getEntryKey, Function.identity(), (a, _) -> a));
        List<SnapshotEntry> toSave = new ArrayList<>(chunk.size());
        for (SnapshotEntry e : chunk) {
            SnapshotEntry existingRow = existing.get(e.getEntryKey());
            toSave.add(existingRow != null
                    ? SnapshotEntry.builder().id(existingRow.getId()).source(source)
                            .entryKey(e.getEntryKey()).payload(e.getPayload()).build()
                    : e);
        }
        List<SnapshotEntry> saved = snapshotEntryRepository.saveAll(toSave);
        snapshotEntryRepository.flush();
        saved.forEach(entityManager::detach);
    }

    private void ingestOsvLine(String line, SourceIngestBuffer buffer) {
        if (line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);
            String key = componentKey(text(node, "ecosystem"), text(node, "name"), text(node, "version"));
            if (key == null) {
                log.warn("[Snapshot] Skipping osv line with missing ecosystem/name/version");
                return;
            }
            if (node.path("_deleted").asBoolean(false)) {
                buffer.add(new ParsedLine(key, null, true));
                return;
            }
            List<SnapshotVuln> vulns = new ArrayList<>();
            for (JsonNode v : node.path("vulns")) {
                if (!v.isObject()) continue;
                vulns.add(new SnapshotVuln(text(v, "osvId"), text(v, "cveId"),
                        text(v, "summary"), text(v, "fixVersion"), text(v, "cweId")));
            }
            buffer.add(new ParsedLine(key, objectMapper.writeValueAsString(vulns), false));
        } catch (Exception e) {
            log.warn("[Snapshot] Skipping malformed osv line: {}", e.getMessage());
        }
    }

    private void ingestDepsDevLine(String line, SourceIngestBuffer versionBuffer, SourceIngestBuffer advisoryBuffer) {
        if (line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = text(node, "type");
            boolean deleted = node.path("_deleted").asBoolean(false);
            if ("version".equals(type)) {
                String key = componentKey(text(node, "ecosystem"), text(node, "name"), text(node, "version"));
                if (key == null) {
                    log.warn("[Snapshot] Skipping depsdev version line with missing ecosystem/name/version");
                    return;
                }
                if (deleted) {
                    versionBuffer.add(new ParsedLine(key, null, true));
                    return;
                }
                SnapshotVersion version = new SnapshotVersion(
                        stringList(node.path("licenses")), stringList(node.path("advisoryKeys")),
                        node.path("isDefault").asBoolean(false),
                        text(node, "deprecated"), text(node, "latestVersion"),
                        number(node, "scorecardScore"));
                versionBuffer.add(new ParsedLine(key, objectMapper.writeValueAsString(version), false));
            } else if ("advisory".equals(type)) {
                String ghsaId = text(node, "ghsaId");
                if (isBlank(ghsaId)) {
                    log.warn("[Snapshot] Skipping depsdev advisory line with missing ghsaId");
                    return;
                }
                String key = ghsaId.strip();
                if (deleted) {
                    advisoryBuffer.add(new ParsedLine(key, null, true));
                    return;
                }
                SnapshotAdvisory advisory = new SnapshotAdvisory(key,
                        text(node, "title"), stringList(node.path("aliases")),
                        number(node, "cvss3Score"), text(node, "cvss3Vector"));
                advisoryBuffer.add(new ParsedLine(key, objectMapper.writeValueAsString(advisory), false));
            } else {
                log.warn("[Snapshot] Skipping depsdev line with unknown type={}", type);
            }
        } catch (Exception e) {
            log.warn("[Snapshot] Skipping malformed depsdev line: {}", e.getMessage());
        }
    }

    private void ingestEpssLine(String line, SourceIngestBuffer buffer) {
        if (line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);
            String cveId = text(node, "cveId");
            if (isBlank(cveId)) {
                log.warn("[Snapshot] Skipping epss line with missing cveId");
                return;
            }
            String key = cveId.strip().toUpperCase(Locale.ROOT);
            if (node.path("_deleted").asBoolean(false)) {
                buffer.add(new ParsedLine(key, null, true));
                return;
            }
            JsonNode score = node.path("score");
            if (!score.isNumber()) {
                log.warn("[Snapshot] Skipping epss line with missing score");
                return;
            }
            buffer.add(new ParsedLine(key, Double.toString(score.asDouble()), false));
        } catch (Exception e) {
            log.warn("[Snapshot] Skipping malformed epss line: {}", e.getMessage());
        }
    }

    private void ingestKevLine(String line, SourceIngestBuffer buffer) {
        if (line.isBlank()) return;
        try {
            JsonNode node = objectMapper.readTree(line);
            String cveId = text(node, "cveId");
            if (isBlank(cveId)) {
                log.warn("[Snapshot] Skipping kev line with missing cveId");
                return;
            }
            String key = cveId.strip().toUpperCase(Locale.ROOT);
            boolean deleted = node.path("_deleted").asBoolean(false);
            buffer.add(new ParsedLine(key, deleted ? null : "1", deleted));
        } catch (Exception e) {
            log.warn("[Snapshot] Skipping malformed kev line: {}", e.getMessage());
        }
    }

    /** Parses {@code meta.json}; returns null for a v1/unversioned bundle (legacy import path). */
    private BundleMetaV2 parseMetaV2(byte[] metaBytes) {
        try {
            JsonNode root = objectMapper.readTree(metaBytes);
            int formatVersion = root.path("formatVersion").asInt(1);
            if (formatVersion < 2) {
                return null;
            }
            Map<String, BundleSourceMeta> sources = new LinkedHashMap<>();
            root.path("sources").fields().forEachRemaining(e -> {
                JsonNode s = e.getValue();
                LocalDate asOf = null;
                String asOfText = text(s, "asOf");
                if (asOfText != null) {
                    try {
                        asOf = LocalDate.parse(asOfText);
                    } catch (Exception ignored) {
                        // Left null — an unparseable asOf is not fatal, just unreported.
                    }
                }
                Integer records = s.path("records").isNumber() ? s.path("records").asInt() : null;
                sources.put(e.getKey(), new BundleSourceMeta(records, asOf, text(s, "origin")));
            });
            Map<String, BundleFileMeta> files = new LinkedHashMap<>();
            root.path("files").fields().forEachRemaining(e -> {
                JsonNode f = e.getValue();
                Integer lines = f.path("lines").isNumber() ? f.path("lines").asInt() : null;
                files.put(e.getKey(), new BundleFileMeta(text(f, "sha256"), lines));
            });
            LocalDateTime builtAt = null;
            String builtAtText = text(root, "builtAt");
            if (builtAtText != null) {
                try {
                    builtAt = LocalDateTime.parse(builtAtText);
                } catch (Exception ignored) {
                    // Left null — same reasoning as asOf above.
                }
            }
            return new BundleMetaV2(text(root, "mode"), builtAt, text(root, "bundleId"), sources, files);
        } catch (Exception e) {
            log.warn("[Snapshot] Failed to parse meta.json as v2 — treating bundle as legacy (v1): {}", e.getMessage());
            return null;
        }
    }

    /**
     * @throws InvalidRequestException on any checksum mismatch — called before any store
     *         mutation. A file listed in {@code meta.json} but absent from this bundle (a
     *         partial/delta bundle covering only some sources) is not an error.
     */
    private void verifyChecksums(BundleMetaV2 meta, Map<String, Path> rawFiles) {
        if (meta.files() == null) return;
        meta.files().forEach((filename, fileMeta) -> {
            Path content = rawFiles.get(filename);
            if (content == null || fileMeta.sha256() == null) return;
            String actual = SnapshotBundleStager.checksum(content);
            if (!fileMeta.sha256().equalsIgnoreCase(actual)) {
                throw new InvalidRequestException("Snapshot bundle integrity check failed for '" + filename
                        + "' (expected sha256=" + fileMeta.sha256() + ", got " + actual
                        + ") — the bundle may be corrupt or tampered with.");
            }
        });
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandatory algorithm (JLS platform guarantee) — this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    // ── Export ───────────────────────────────────────────────────────────

    /**
     * Builds a v2 snapshot bundle from the data this instance has already fetched
     * (libraries + CVEs). Run on an ONLINE instance, then import on the air-gapped one.
     * Only deps.dev-resolved libraries ({@code isLatestVersion != null}) contribute
     * version records; advisory records and version-record {@code advisoryKeys} are keyed
     * purely on GHSA-format ids — no longer gated on whether an advisory record itself
     * exists, since "does this component have a GHSA id" and "do we have advisory detail for
     * that id" are independent facts.
     */
    @Transactional(readOnly = true)
    public byte[] exportBundle() {
        long upperId = libraryRepository.findSnapshotUpperId();
        long afterId = 0;
        int exportedLibraries = 0;

        StringBuilder osv = new StringBuilder();
        StringBuilder githubAdvisory = new StringBuilder();
        StringBuilder nvd = new StringBuilder();
        StringBuilder depsdev = new StringBuilder();
        Map<String, Double> epss = new LinkedHashMap<>();
        Set<String> kev = new LinkedHashSet<>();
        Map<String, SnapshotAdvisory> advisories = new LinkedHashMap<>();
        int osvRecords = 0;
        int githubAdvisoryRecords = 0;
        int nvdRecords = 0;
        int versionRecords = 0;

        while (afterId < upperId) {
            List<Long> ids = libraryRepository.findSnapshotIds(afterId, upperId,
                    org.springframework.data.domain.PageRequest.of(0, SAVE_CHUNK_SIZE));
            if (ids.isEmpty()) break;
            List<Library> libraries = libraryRepository.findByIdInWithCves(ids);
            exportedLibraries += libraries.size();
            for (Library lib : libraries) {
                String key = componentKey(lib.getEcosystem(), lib.getName(), lib.getVersion());
                if (key == null) continue;
                List<Cve> cves = lib.getCves();

                // Vulnerability records split by upstream source so the offline clients can each
                // read the source they were built for. CVEs with no recorded source are treated as
                // OSV-only for backward compatibility with rows enriched before multi-source tracking.
                List<Cve> osvCves = new ArrayList<>();
                List<Cve> ghCves = new ArrayList<>();
                List<Cve> nvdCves = new ArrayList<>();
                for (Cve c : cves) {
                    java.util.Set<CveSource> srcs = c.getSources();
                    boolean hasSources = srcs != null && !srcs.isEmpty();
                    if (!hasSources || srcs.contains(CveSource.OSV)) osvCves.add(c);
                    if (hasSources && srcs.contains(CveSource.GITHUB_ADVISORY)) ghCves.add(c);
                    if (hasSources && srcs.contains(CveSource.NVD)) nvdCves.add(c);
                }
                if (!osvCves.isEmpty()) {
                    osvRecords += appendVulnLines(osv, lib, osvCves);
                }
                if (!ghCves.isEmpty()) {
                    githubAdvisoryRecords += appendVulnLines(githubAdvisory, lib, ghCves);
                }
                if (!nvdCves.isEmpty()) {
                    nvdRecords += appendVulnLines(nvd, lib, nvdCves);
                }

                // depsdev.jsonl version record — only when deps.dev GetVersion resolved.
                // advisoryKeys includes every GHSA-format id regardless of whether we also have
                // advisory detail for it — an OSV-only finding with a GHSA alias still belongs in
                // the version record's key list; the *advisory* record is a separate concern.
                List<String> advisoryKeys = cves.stream()
                        .filter(c -> c.getGhsaId() != null && c.getGhsaId().startsWith("GHSA-"))
                        .map(Cve::getGhsaId)
                        .distinct()
                        .toList();
                if (lib.getIsLatestVersion() != null) {
                    // Prefer the preserved pre-join license list; legacy rows enriched
                    // before licenseExpressionRaw existed fall back to re-splitting licenseName on
                    // " AND " (the same lossy heuristic as before, only for rows with no better data).
                    List<String> licenses = lib.getLicenseExpressionRaw() != null
                            ? lib.getLicenseExpressionRaw()
                            : (isBlank(lib.getLicenseName()) ? List.of() : List.of(lib.getLicenseName().split(" AND ")));
                    SnapshotVersion version = new SnapshotVersion(
                            licenses,
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
                    // depsdev.jsonl advisory records (deduped — CVEs are per-library). Still gated on
                    // hasAdvisoryData: this map specifically means "we have deps.dev advisory detail",
                    // independent from the version record's advisoryKeys list above.
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

            // Output builders contain values only; release each batch's managed entity graph.
            for (Library library : libraries) {
                library.getCves().forEach(entityManager::detach);
                entityManager.detach(library);
            }
            afterId = ids.getLast();
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

        String osvContent = osv.toString();
        String githubAdvisoryContent = githubAdvisory.toString();
        String nvdContent = nvd.toString();
        String depsdevContent = depsdev.toString();
        String epssContent = epssLines.toString();
        String kevContent = kevLines.toString();

        String bundleId = UUID.randomUUID().toString();
        LocalDateTime builtAt = LocalDateTime.now();
        // This export is derived entirely from already-scanned data, not a fresh upstream pull —
        // "asOf" is therefore only as fresh as this instance's own enrichment, approximated here
        // as "now" (the export moment). EXPORT_ORIGIN discloses the derivation on every source.
        LocalDate asOf = LocalDate.now();

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("format", BUNDLE_FORMAT);
        meta.put("formatVersion", CURRENT_FORMAT_VERSION);
        meta.put("bundleId", bundleId);
        meta.put("mode", "full");
        meta.put("builtAt", builtAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("builder", "oswl-airgapped-export");
        ObjectNode sources = meta.putObject("sources");
        putSourceMeta(sources, SOURCE_OSV, osvRecords, asOf);
        putSourceMeta(sources, SOURCE_DEPSDEV_VERSION, versionRecords, asOf);
        putSourceMeta(sources, SOURCE_DEPSDEV_ADVISORY, advisories.size(), asOf);
        putSourceMeta(sources, SOURCE_GITHUB_ADVISORY, githubAdvisoryRecords, asOf);
        putSourceMeta(sources, SOURCE_NVD, nvdRecords, asOf);
        putSourceMeta(sources, SOURCE_EPSS, epss.size(), asOf);
        putSourceMeta(sources, SOURCE_KEV, kev.size(), asOf);
        ObjectNode files = meta.putObject("files");
        putFileMeta(files, "osv.jsonl", osvContent, osvRecords);
        putFileMeta(files, "depsdev.jsonl", depsdevContent, versionRecords + advisories.size());
        putFileMeta(files, "github-advisory.jsonl", githubAdvisoryContent, githubAdvisoryRecords);
        putFileMeta(files, "nvd.jsonl", nvdContent, nvdRecords);
        putFileMeta(files, "epss.jsonl", epssContent, epss.size());
        putFileMeta(files, "kev.jsonl", kevContent, kev.size());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
                writeZipEntry(zos, "meta.json", writeJson(meta));
                writeZipEntry(zos, "osv.jsonl", osvContent);
                writeZipEntry(zos, "depsdev.jsonl", depsdevContent);
                writeZipEntry(zos, "github-advisory.jsonl", githubAdvisoryContent);
                writeZipEntry(zos, "nvd.jsonl", nvdContent);
                writeZipEntry(zos, "epss.jsonl", epssContent);
                writeZipEntry(zos, "kev.jsonl", kevContent);
            }
            log.info("[Snapshot] Exported offline snapshot bundleId={}: {} libraries, {} osv, {} github-advisory, {} nvd, {} version records, {} advisories, {} epss, {} kev",
                    bundleId, exportedLibraries, osvRecords, githubAdvisoryRecords, nvdRecords,
                    versionRecords, advisories.size(), epss.size(), kev.size());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build snapshot bundle: " + e.getMessage(), e);
        }
    }

    private int appendVulnLines(StringBuilder target, Library lib, List<Cve> cves) {
        List<SnapshotVuln> vulns = cves.stream()
                .map(c -> new SnapshotVuln(c.getGhsaId(), c.getCveId(), c.getSummary(),
                        c.getFixVersion(), c.getCweId(),
                        c.getSeverity() != null ? c.getSeverity().name() : null,
                        c.getCvssScore(), c.getCvss3Vector(),
                        c.getMatchConfidence() != null ? c.getMatchConfidence().name() : null))
                .toList();
        ObjectNode line = objectMapper.createObjectNode();
        line.put("ecosystem", lib.getEcosystem());
        line.put("name", lib.getName());
        line.put("version", lib.getVersion());
        line.set("vulns", objectMapper.valueToTree(vulns));
        target.append(writeJson(line)).append('\n');
        return 1;
    }

    private static void putSourceMeta(ObjectNode sources, String source, int records, LocalDate asOf) {
        ObjectNode s = sources.putObject(source);
        s.put("records", records);
        s.put("asOf", asOf.toString());
        s.put("origin", EXPORT_ORIGIN);
    }

    private void putFileMeta(ObjectNode files, String filename, String content, int lines) {
        ObjectNode f = files.putObject(filename);
        f.put("sha256", sha256Hex(content.getBytes(StandardCharsets.UTF_8)));
        f.put("lines", lines);
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
