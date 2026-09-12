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
import java.util.Objects;
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
 *   <li>{@code 2} and {@code 3} — {@code bundleId}/{@code mode}/{@code builtAt}/{@code sources[].asOf}/
 *       {@code sources[].origin}/{@code files[].sha256} are read and enforced (checksum mismatch
 *       rejects the whole bundle before any store mutation).</li>
 *   <li>{@code 3} requires original-aware OSV evaluation, including currently unaffected constraints.</li>
 *   <li>missing or {@code 1} — legacy import without integrity provenance; supplied notices are still retained.</li>
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
    public static final String SOURCE_COCOAPODS_SPECS = "cocoapods-specs";
    public static final List<String> SOURCES = List.of(
            SOURCE_OSV, SOURCE_DEPSDEV_VERSION, SOURCE_DEPSDEV_ADVISORY, SOURCE_GITHUB_ADVISORY,
            SOURCE_NVD, SOURCE_EPSS, SOURCE_KEV, SOURCE_UNRESOLVED, SOURCE_COCOAPODS_SPECS);

    private static final String BUNDLE_FORMAT = "oswl-vdb";
    private static final int CURRENT_FORMAT_VERSION = 3;
    private static final String EXPORT_ORIGIN = "derived-from-scan";

    private static final int SAVE_CHUNK_SIZE = 500;

    private static final Set<String> KNOWN_DATA_FILES =
            Set.of("osv.jsonl", "depsdev.jsonl", "github-advisory.jsonl", "nvd.jsonl",
                    "epss.jsonl", "kev.jsonl", "unresolved.jsonl", "cocoapods-specs.jsonl");

    private final SnapshotEntryRepository snapshotEntryRepository;
    private final SnapshotMetaRepository snapshotMetaRepository;

    @org.springframework.beans.factory.annotation.Value("${oswl.airgapped.staleness-warn-days:7}")
    private int stalenessWarnDays = 7;
    private final LibraryRepository libraryRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private final SnapshotGenerationService generations;
    /** Local instance (codebase convention — matches DepsDevClient/GitHubService); avoids a bean dependency. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── DTO ──────────────────────────────────────────────────────────────

    /**
     * Vulnerability entry stored per component. Originally modelled on {@code OsvClient.OsvVuln},
     * now also carries severity/CVSS/confidence so it can represent GitHub Advisory and
     * NVD matches in the same offline JSON array.
     */
    public record SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId,
                                String severity, Double cvssScore, String cvss3Vector, String matchConfidence,
                                Set<String> fixVersionConflictCandidates, JsonNode osvAdvisory) {
        public SnapshotVuln {
            if (osvAdvisory != null && !osvAdvisory.isNull()) {
                if (!osvAdvisory.isObject() || !osvAdvisory.path("id").isTextual()
                        || !Objects.equals(osvId, osvAdvisory.path("id").asText())
                        || !osvAdvisory.path("affected").isArray() || !osvAdvisory.path("modified").isTextual()) {
                    throw new IllegalArgumentException("OSV evidence requires matching identity, modified time and affected entries");
                }
                java.time.Instant.parse(osvAdvisory.path("modified").asText());
                osvAdvisory = osvAdvisory.deepCopy();
            } else {
                osvAdvisory = null;
            }
            fixVersionConflictCandidates = fixVersionConflictCandidates == null ? Set.of() : Set.copyOf(fixVersionConflictCandidates);
            if (fixVersionConflictCandidates.stream().anyMatch(v -> v.isBlank() || v.length() > 100))
                throw new IllegalArgumentException("Invalid conflicting fix candidate");
            if (!fixVersionConflictCandidates.isEmpty()) fixVersion = null;
        }
        public SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId,
                String severity, Double cvssScore, String cvss3Vector, String matchConfidence,
                Set<String> fixVersionConflictCandidates) {
            this(osvId, cveId, summary, fixVersion, cweId, severity, cvssScore, cvss3Vector, matchConfidence,
                    fixVersionConflictCandidates, null);
        }
        @Override public JsonNode osvAdvisory() { return osvAdvisory == null ? null : osvAdvisory.deepCopy(); }
        public SnapshotVuln(String osvId, String cveId, String summary, String fixVersion, String cweId,
                String severity, Double cvssScore, String cvss3Vector, String matchConfidence) {
            this(osvId, cveId, summary, fixVersion, cweId, severity, cvssScore, cvss3Vector, matchConfidence, Set.of());
        }
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
                                Map<String, BundleSourceMeta> sources, Map<String, BundleFileMeta> files, int formatVersion) {}

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

    @Transactional(readOnly = true)
    public java.util.Optional<com.salkcoding.oswl.dto.snapshot.CocoaPodsSpec> findCocoaPodsSpec(String name, String version) {
        String key = componentKey("COCOAPODS", name, version);
        if (key == null) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(parsePayloads(SOURCE_COCOAPODS_SPECS, List.of(key),
                com.salkcoding.oswl.dto.snapshot.CocoaPodsSpec.class).get(key));
    }

    @Transactional(readOnly = true)
    public Set<String> findUnresolvedKeys(Collection<String> keys) {
        return findPayloads(SOURCE_UNRESOLVED, keys).keySet();
    }

    /** OSV vulns per component key; absent keys are unresolved, unlike a stored empty result. */
    @Transactional(readOnly = true)
    public Map<String, List<SnapshotVuln>> findOsvVulns(Collection<String> componentKeys) {
        return parseVulnLists(SOURCE_OSV, componentKeys);
    }

    public record VulnerabilitySnapshotView(Map<String, List<SnapshotVuln>> findings, Set<String> unresolvedKeys, boolean stale,
                                  java.time.Instant validUntil) {}

    /** Do not combine old findings with coverage or dates published by a concurrent import. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public VulnerabilitySnapshotView readOsvSnapshot(Collection<String> componentKeys) {
        return new VulnerabilitySnapshotView(findOsvVulns(componentKeys), findUnresolvedKeys(componentKeys),
                isSourceStaleOrUndated(SOURCE_OSV), sourceEvidenceValidUntil(SOURCE_OSV));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public VulnerabilitySnapshotView readNvdSnapshot(Collection<String> componentKeys) {
        return new VulnerabilitySnapshotView(findNvdVulns(componentKeys), findUnresolvedKeys(componentKeys),
                isSourceStaleOrUndated(SOURCE_NVD), sourceEvidenceValidUntil(SOURCE_NVD));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public VulnerabilitySnapshotView readGitHubAdvisorySnapshot(Collection<String> componentKeys) {
        return new VulnerabilitySnapshotView(findGitHubAdvisoryVulns(componentKeys), findUnresolvedKeys(componentKeys),
                isSourceStaleOrUndated(SOURCE_GITHUB_ADVISORY), sourceEvidenceValidUntil(SOURCE_GITHUB_ADVISORY));
    }

    public record VersionSnapshotView(Map<String, SnapshotVersion> findings, Set<String> unresolvedKeys, boolean stale) {}
    public record AdvisorySnapshotView(Map<String, SnapshotAdvisory> findings, boolean stale) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public VersionSnapshotView readVersionSnapshot(Collection<String> componentKeys) {
        return new VersionSnapshotView(findVersions(componentKeys), findUnresolvedKeys(componentKeys),
                isSourceStaleOrUndated(SOURCE_DEPSDEV_VERSION));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public AdvisorySnapshotView readAdvisorySnapshot(Collection<String> ghsaIds) {
        return new AdvisorySnapshotView(findAdvisories(ghsaIds), isSourceStaleOrUndated(SOURCE_DEPSDEV_ADVISORY));
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
                JsonNode rawVulns = objectMapper.readTree(payload);
                if (rawVulns == null || !rawVulns.isArray()) throw new IllegalArgumentException("Invalid stored vulnerability list");
                for (JsonNode raw : rawVulns) readFixConflicts(raw);
                List<SnapshotVuln> vulns = objectMapper.convertValue(rawVulns, new TypeReference<>() {});
                if (vulns == null || vulns.stream().anyMatch(v -> v == null
                        || (isBlank(v.osvId()) && isBlank(v.cveId())))) {
                    throw new IllegalArgumentException("Invalid stored vulnerability list");
                }
                result.put(key, vulns);
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
                double score = Double.parseDouble(payload.trim());
                if (!Double.isFinite(score) || score < 0 || score > 1)
                    throw new NumberFormatException("Invalid EPSS probability");
                result.put(key, score);
            } catch (NumberFormatException e) {
                log.warn("[Snapshot] Skipping corrupt epss entry key={}", key);
            }
        });
        return result;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public Map<String, Double> readEpssSnapshot(Collection<String> cveIds) {
        Map<String, Double> scores = findEpssScores(cveIds);
        return isSourceStaleOrUndated(SOURCE_EPSS) ? Map.of() : scores;
    }

    public record KevSnapshotView(Set<String> ids, java.time.Instant validUntil) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.SERIALIZABLE,
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public KevSnapshotView readKevSnapshot() {
        return new KevSnapshotView(loadKevCveIds(), sourceEvidenceValidUntil(SOURCE_KEV));
    }

    /** All KEV-listed CVE ids in the store (uppercase). */
    @Transactional(readOnly = true)
    public Set<String> loadKevCveIds() {
        var scope = SnapshotGenerationScope.current();
        return scope == null ? snapshotEntryRepository.findEntryKeysBySource(SOURCE_KEV)
                : generations.keys(scope.generationId(), SOURCE_KEV);
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
     * when the bundle/import happened, not how fresh the upstream data itself is). Null when
     * no sources were imported or any imported source has an unknown or future date.
     */
    @Transactional(readOnly = true)
    public LocalDate oldestSourceAsOf() {
        List<SnapshotMeta> metadata = snapshotMetaRepository.findAll();
        LocalDate today = LocalDate.now();
        if (metadata.stream().anyMatch(source -> source.getSourceAsOf() == null
                || source.getSourceAsOf().isAfter(today))) return null;
        return metadata.stream()
                .map(SnapshotMeta::getSourceAsOf)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    private LocalDate sourceDate(String source) {
        var scope = SnapshotGenerationScope.current();
        return scope == null ? snapshotMetaRepository.findById(source).map(SnapshotMeta::getSourceAsOf).orElse(null)
                : scope.sourceDate(source);
    }

    @Transactional(readOnly = true)
    public Boolean pinnedKevStatus(String cveId) {
        if (SnapshotGenerationScope.current() == null) throw new IllegalStateException("Pinned KEV lookup requires a generation");
        if (findPayloads(SOURCE_KEV, List.of(cveId)).containsKey(cveId)) return true;
        return isSourceStaleOrUndated(SOURCE_KEV) ? null : Boolean.FALSE;
    }

    /** Missing, future, or stale provenance cannot establish current lookup coverage. */
    @Transactional(readOnly = true)
    public boolean isSourceStaleOrUndated(String source) {
        LocalDate today = LocalDate.now();
        LocalDate asOf = sourceDate(source);
        return asOf == null || asOf.isAfter(today) || stalenessWarnDays < 0
                || java.time.temporal.ChronoUnit.DAYS.between(asOf, today) > stalenessWarnDays;
    }

    /** Exclusive expiry under the same calendar-day policy used for snapshot coverage. */
    @Transactional(readOnly = true)
    public java.time.Instant sourceEvidenceValidUntil(String source) {
        LocalDate asOf = sourceDate(source);
        if (asOf == null || asOf.isAfter(LocalDate.now()) || stalenessWarnDays < 0) return null;
        return asOf.plusDays((long) stalenessWarnDays + 1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
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
        var scope = SnapshotGenerationScope.current();
        if (scope != null) return generations.payloads(scope.generationId(), source, keys);
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
            byte[] metaBytes = metaFile == null ? null : Files.readAllBytes(metaFile);
            BundleMetaV2 meta = metaBytes == null ? null : parseMetaV2(metaBytes);
            Set<JsonNode> notices = incomingNotices(metaBytes);
            if (rawFiles.containsKey("cocoapods-specs.jsonl") && meta == null)
                throw new InvalidRequestException("CocoaPods specs require a version 2 bundle with checksums");
            if (meta != null) {
                Set<String> expectedEntries = new LinkedHashSet<>(rawFiles.keySet());
                expectedEntries.add("meta.json");
                if (!staged.entryNames().equals(expectedEntries))
                    throw new InvalidRequestException("Snapshot v2 contains unrecognized or nested file entries");
                verifyChecksums(meta, rawFiles);
            }
            // Validate line budgets before a REPLACE is allowed to delete existing source data.
            rawFiles.forEach((filename, file) -> {
                long[] lines = {0};
                SnapshotBundleStager.forEachLine(file, ignored -> lines[0]++);
                if (meta != null && lines[0] != meta.files().get(filename).lines())
                    throw new InvalidRequestException("Snapshot line count mismatch for '" + filename + "'");
            });
            ImportMode mode = requestedMode != null ? requestedMode : resolveModeFromMeta(meta);
            SnapshotBundleStager.checkInterrupted();
            generations.initialize();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.setTimeout(300);
            // Retained dates, notices and rows must be based on one publication state.
            transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
            transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            for (int attempt = 1; ; attempt++) {
                SnapshotBundleStager.checkInterrupted();
                try {
                    SnapshotImportResult result = transaction.execute(status -> applyBundle(rawFiles, meta, mode, notices));
                    log.info("[Snapshot] Imported offline snapshot ({} mode): {} records across {}", mode,
                            result.totalRecords(), result.sources());
                    return result;
                } catch (org.springframework.dao.ConcurrencyFailureException conflict) {
                    if (attempt >= 3) throw conflict;
                    log.warn("[Snapshot] Concurrent publication rolled back; retrying import ({}/3)", attempt + 1);
                }
            }
        } catch (IOException e) {
            throw new InvalidRequestException("Snapshot bundle is not a readable zip: " + e.getMessage());
        }
    }

    private SnapshotImportResult applyBundle(Map<String, Path> rawFiles, BundleMetaV2 meta, ImportMode mode, Set<JsonNode> notices) {
        Map<String, LocalDate> retainedDates = new LinkedHashMap<>();
        if (mode == ImportMode.MERGE) {
            for (String source : SOURCES) {
                if (snapshotEntryRepository.countBySource(source) > 0) {
                    retainedDates.put(source, snapshotMetaRepository.findById(source)
                            .map(SnapshotMeta::getSourceAsOf).orElse(null));
                }
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<String, Path> rf : rawFiles.entrySet()) {
            SnapshotBundleStager.checkInterrupted();
            switch (rf.getKey()) {
                case "cocoapods-specs.jsonl" -> {
                    SourceIngestBuffer buf = new SourceIngestBuffer(SOURCE_COCOAPODS_SPECS, mode);
                    int[] lines = {0};
                    SnapshotBundleStager.forEachLine(rf.getValue(), line -> {
                        if (line.isBlank()) return;
                        if (++lines[0] > 1000) throw new InvalidRequestException("CocoaPods bundle limit is 1000 specs");
                        try {
                            var spec = objectMapper.readValue(line, com.salkcoding.oswl.dto.snapshot.CocoaPodsSpec.class);
                            spec.validate();
                            buf.add(new ParsedLine(componentKey("COCOAPODS", spec.name(), spec.version()), writeJson(objectMapper.valueToTree(spec)), false));
                        } catch (InvalidRequestException e) { throw e;
                        } catch (Exception e) { throw new InvalidRequestException("Malformed CocoaPods spec record"); }
                    });
                    counts.merge(SOURCE_COCOAPODS_SPECS, buf.finish(), Integer::sum);
                    if (snapshotEntryRepository.countBySource(SOURCE_COCOAPODS_SPECS) > 1000)
                        throw new InvalidRequestException("CocoaPods store limit is 1000 specs");
                }
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
            Set<JsonNode> retainedNotices = new LinkedHashSet<>();
            if (mode == ImportMode.MERGE) snapshotMetaRepository.findById(source)
                    .ifPresent(previous -> retainedNotices.addAll(storedNotices(previous.getDataNotices())));
            retainedNotices.addAll(notices);
            builder.dataNotices(retainedNotices.isEmpty() ? null : writeJson(objectMapper.valueToTree(retainedNotices)));
            if (meta != null) {
                BundleSourceMeta sourceMeta = meta.sources() != null ? meta.sources().get(source) : null;
                LocalDate asOf = sourceMeta != null ? sourceMeta.asOf() : null;
                if (retainedDates.containsKey(source)) {
                    // A delta does not prove that every retained row was refreshed.
                    LocalDate retained = retainedDates.get(source);
                    asOf = asOf == null || retained == null ? null : (retained.isBefore(asOf) ? retained : asOf);
                }
                builder.bundleId(meta.bundleId())
                        .builtAt(meta.builtAt())
                        .sourceAsOf(asOf)
                        .origin(sourceMeta != null ? sourceMeta.origin() : null)
                        .formatVersion(meta.formatVersion());
            }
            snapshotMetaRepository.save(builder.build());
        }
        checkNoticeBudget(allStoredNotices());
        entityManager.flush();
        generations.publish();
        return new SnapshotImportResult(counts, total, mode.name());
    }

    private static ImportMode resolveModeFromMeta(BundleMetaV2 meta) {
        if (meta != null && "delta".equalsIgnoreCase(meta.mode())) {
            return ImportMode.MERGE;
        }
        return ImportMode.REPLACE;
    }

    private Set<JsonNode> incomingNotices(byte[] metaBytes) throws IOException {
        Set<JsonNode> notices = new LinkedHashSet<>();
        if (metaBytes == null) return notices;
        JsonNode root = objectMapper.readTree(metaBytes);
        if (root.has("dataNotices")) {
            if (!root.path("dataNotices").isObject()) throw new InvalidRequestException("Snapshot dataNotices must be an object");
            notices.add(root.path("dataNotices"));
        }
        if (root.has("upstreamDataNotices")) notices.addAll(noticeArray(root.path("upstreamDataNotices")));
        checkNoticeBudget(notices);
        return notices;
    }

    private Set<JsonNode> allStoredNotices() {
        Set<JsonNode> notices = new LinkedHashSet<>();
        snapshotMetaRepository.findAll().forEach(source -> notices.addAll(storedNotices(source.getDataNotices())));
        return notices;
    }

    private void checkNoticeBudget(Set<JsonNode> notices) {
        // Leave space for provenance and checksums under the importer's 1 MiB metadata limit.
        if (writeJson(objectMapper.valueToTree(notices)).getBytes(StandardCharsets.UTF_8).length > 512 * 1024)
            throw new InvalidRequestException("Snapshot notices exceed the 512 KiB retention limit; no notices were discarded");
    }

    private Set<JsonNode> storedNotices(String payload) {
        if (payload == null) return Set.of();
        try {
            return noticeArray(objectMapper.readTree(payload));
        } catch (IOException invalid) {
            throw new InvalidRequestException("Stored snapshot notices are unreadable; refusing to discard them");
        }
    }

    private Set<JsonNode> noticeArray(JsonNode array) {
        if (array == null || !array.isArray()) throw new InvalidRequestException("Snapshot upstreamDataNotices must be an array");
        Set<JsonNode> notices = new LinkedHashSet<>();
        for (JsonNode notice : array) {
            if (!notice.isObject()) throw new InvalidRequestException("Snapshot upstream notices must be objects");
            notices.add(notice);
        }
        return notices;
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
        // MERGE applies records in input order; collapse repeated keys before inserting new rows.
        Map<String, SnapshotEntry> latest = new LinkedHashMap<>();
        chunk.forEach(entry -> latest.put(entry.getEntryKey(), entry));
        Set<String> keys = latest.keySet();
        Map<String, SnapshotEntry> existing = snapshotEntryRepository.findBySourceAndEntryKeyIn(source, keys).stream()
                .collect(Collectors.toMap(SnapshotEntry::getEntryKey, Function.identity(), (a, _) -> a));
        List<SnapshotEntry> toSave = new ArrayList<>(latest.size());
        for (SnapshotEntry e : latest.values()) {
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
                throw new InvalidRequestException("Snapshot record requires ecosystem/name/version");
            }
            if (node.has("_deleted") && !node.path("_deleted").isBoolean())
                throw new InvalidRequestException("Snapshot deletion marker must be boolean");
            if (node.path("_deleted").asBoolean(false)) {
                buffer.add(new ParsedLine(key, null, true));
                return;
            }
            List<SnapshotVuln> vulns = new ArrayList<>();
            if (!SOURCE_UNRESOLVED.equals(buffer.source) && !node.path("vulns").isArray())
                throw new InvalidRequestException("Snapshot vulnerability record requires a vulns array");
            for (JsonNode v : node.path("vulns")) {
                if (!v.isObject() || (isBlank(text(v, "osvId")) && isBlank(text(v, "cveId"))))
                    throw new InvalidRequestException("Snapshot vulnerability requires an advisory identity");
                vulns.add(new SnapshotVuln(text(v, "osvId"), text(v, "cveId"),
                        text(v, "summary"), text(v, "fixVersion"), text(v, "cweId"),
                        text(v, "severity"), number(v, "cvssScore"), text(v, "cvss3Vector"), text(v, "matchConfidence"), readFixConflicts(v), v.get("osvAdvisory")));
            }
            buffer.add(new ParsedLine(key, objectMapper.writeValueAsString(vulns), false));
        } catch (Exception e) {
            throw new InvalidRequestException("Malformed snapshot vulnerability record: " + e.getMessage());
        }
    }

    private static Set<String> readFixConflicts(JsonNode vuln) {
        if (!vuln.has("fixVersionConflictCandidates")) return Set.of();
        JsonNode values = vuln.path("fixVersionConflictCandidates");
        if (!values.isArray()) throw new IllegalArgumentException("Fix conflict candidates must be an array");
        Set<String> candidates = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 100)
                throw new IllegalArgumentException("Invalid fix conflict candidate");
            candidates.add(value.asText());
        }
        return candidates;
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
                throw new InvalidRequestException("Snapshot EPSS record requires cveId");
            }
            String key = cveId.strip().toUpperCase(Locale.ROOT);
            if (node.has("_deleted") && !node.path("_deleted").isBoolean())
                throw new InvalidRequestException("Snapshot EPSS deletion marker must be boolean");
            if (node.path("_deleted").asBoolean(false)) {
                buffer.add(new ParsedLine(key, null, true));
                return;
            }
            JsonNode score = node.path("score");
            if (!score.isNumber() || !Double.isFinite(score.asDouble()) || score.asDouble() < 0 || score.asDouble() > 1) {
                throw new InvalidRequestException("Snapshot EPSS score must be a finite probability from 0 to 1");
            }
            buffer.add(new ParsedLine(key, Double.toString(score.asDouble()), false));
        } catch (Exception e) {
            throw new InvalidRequestException("Malformed snapshot EPSS record: " + e.getMessage());
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
            if (root == null || !root.isObject()) throw new InvalidRequestException("Snapshot metadata must be an object");
            JsonNode declaredVersion = root.path("formatVersion");
            if (declaredVersion.isMissingNode()) return null;
            if (!declaredVersion.isIntegralNumber() || !declaredVersion.canConvertToInt()
                    || declaredVersion.intValue() < 1 || declaredVersion.intValue() > CURRENT_FORMAT_VERSION)
                throw new InvalidRequestException("Unsupported snapshot formatVersion");
            if (declaredVersion.intValue() == 1) return null;
            if (!root.path("files").isObject()) throw new InvalidRequestException("Snapshot v2 requires a files manifest");
            Map<String, BundleSourceMeta> sources = new LinkedHashMap<>();
            root.path("sources").fields().forEachRemaining(e -> {
                JsonNode s = e.getValue();
                LocalDate asOf = null;
                if (s.hasNonNull("asOf")) {
                    JsonNode declaredDate = s.path("asOf");
                    if (!declaredDate.isTextual() || declaredDate.asText().isBlank())
                        throw new InvalidRequestException("Snapshot source asOf must be a date string");
                    asOf = LocalDate.parse(declaredDate.asText());
                    if (asOf.isAfter(LocalDate.now()))
                        throw new InvalidRequestException("Snapshot source asOf cannot be in the future");
                }
                Integer records = s.path("records").isNumber() ? s.path("records").asInt() : null;
                sources.put(e.getKey(), new BundleSourceMeta(records, asOf, text(s, "origin")));
            });
            Map<String, BundleFileMeta> files = new LinkedHashMap<>();
            root.path("files").fields().forEachRemaining(e -> {
                JsonNode f = e.getValue();
                JsonNode declaredLines = f.path("lines");
                if (!declaredLines.isIntegralNumber() || !declaredLines.canConvertToInt() || declaredLines.intValue() < 0)
                    throw new InvalidRequestException("Snapshot integrity requires a nonnegative integer line count");
                Integer lines = declaredLines.intValue();
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
            return new BundleMetaV2(text(root, "mode"), builtAt, text(root, "bundleId"), sources, files, declaredVersion.intValue());
        } catch (Exception e) {
            throw new InvalidRequestException("Invalid snapshot metadata: " + e.getMessage());
        }
    }

    /**
     * @throws InvalidRequestException on a file inventory or checksum mismatch before any store
     *         mutation. Partial/delta bundles declare only the data files they actually include.
     */
    private void verifyChecksums(BundleMetaV2 meta, Map<String, Path> rawFiles) {
        if (!meta.files().keySet().equals(rawFiles.keySet()))
            throw new InvalidRequestException("Snapshot manifest files do not match the archive");
        rawFiles.keySet().forEach(filename -> {
            BundleFileMeta fileMeta = meta.files().get(filename);
            if (fileMeta == null || fileMeta.sha256() == null || !fileMeta.sha256().matches("[0-9a-fA-F]{64}"))
                throw new InvalidRequestException("Snapshot bundle integrity requires SHA-256 for '" + filename + "'");
        });
        meta.files().forEach((filename, fileMeta) -> {
            Path content = rawFiles.get(filename);
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
        StringBuilder unresolved = new StringBuilder();
        int unresolvedRecords = 0;
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
        boolean hasOriginals = false;

        while (afterId < upperId) {
            List<Long> ids = libraryRepository.findSnapshotIds(afterId, upperId,
                    org.springframework.data.domain.PageRequest.of(0, SAVE_CHUNK_SIZE));
            if (ids.isEmpty()) break;
            List<Library> libraries = libraryRepository.findByIdInWithCves(ids);
            Map<String, List<SnapshotVuln>> storedOriginals = findOsvVulns(libraries.stream()
                    .map(this::osvExportKey).filter(Objects::nonNull).toList());
            exportedLibraries += libraries.size();
            for (Library lib : libraries) {
                String key = componentKey(lib.getEcosystem(), lib.getName(), lib.getVersion());
                if (key == null) continue;
                List<Cve> cves = lib.getCves();
                if (!lib.isVulnerabilitiesAnalyzed()) {
                    unresolvedRecords += appendVulnLines(unresolved, lib, List.of());
                }

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
                if (!osvCves.isEmpty() || (lib.getVulnerabilityLookupOutcomes() != null && "RESOLVED".equals(lib.getVulnerabilityLookupOutcomes().get("OSV")))) {
                    List<SnapshotVuln> originals = exportableOriginals(lib, osvCves, storedOriginals.get(osvExportKey(lib)));
                    hasOriginals |= originals != null;
                    osvRecords += originals == null ? appendVulnLines(osv, lib, osvCves, true)
                            : appendSnapshotVulnLine(osv, lib, originals, true);
                }
                if (!ghCves.isEmpty() || (lib.getVulnerabilityLookupOutcomes() != null && "RESOLVED".equals(lib.getVulnerabilityLookupOutcomes().get("GITHUB_ADVISORY")))) {
                    githubAdvisoryRecords += appendVulnLines(githubAdvisory, lib, ghCves);
                }
                if (!nvdCves.isEmpty() || (lib.getVulnerabilityLookupOutcomes() != null && "RESOLVED".equals(lib.getVulnerabilityLookupOutcomes().get("NVD")))) {
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
        Set<String> specKeys = snapshotEntryRepository.findEntryKeysBySource(SOURCE_COCOAPODS_SPECS);
        if (specKeys.size() > 1000) throw new InvalidRequestException("CocoaPods store limit is 1000 specs");
        StringBuilder specsContent = new StringBuilder();
        if (!specKeys.isEmpty()) {
            for (SnapshotEntry entry : snapshotEntryRepository.findBySourceAndEntryKeyIn(SOURCE_COCOAPODS_SPECS, specKeys)) {
                specsContent.append(entry.getPayload()).append('\n');
            }
        }
        String githubAdvisoryContent = githubAdvisory.toString();
        String nvdContent = nvd.toString();
        String depsdevContent = depsdev.toString();
        String epssContent = epssLines.toString();
        String kevContent = kevLines.toString();

        String bundleId = UUID.randomUUID().toString();
        LocalDateTime builtAt = LocalDateTime.now();
        // Scan-derived rows do not carry a verified upstream date. Repackaging cannot
        // establish freshness; only directly preserved source records retain their date.

        ObjectNode meta = objectMapper.createObjectNode();
        meta.put("format", BUNDLE_FORMAT);
        meta.put("formatVersion", hasOriginals ? CURRENT_FORMAT_VERSION : 2);
        meta.put("bundleId", bundleId);
        meta.put("mode", "full");
        meta.put("builtAt", builtAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        meta.put("builder", "oswl-airgapped-export");
        ObjectNode dataNotices = meta.putObject("dataNotices");
        Set<JsonNode> upstreamNotices = allStoredNotices();
        checkNoticeBudget(upstreamNotices);
        meta.set("upstreamDataNotices", objectMapper.valueToTree(upstreamNotices));
        dataNotices.put("scope", "These notices are not a redistribution clearance for the bundle or its other data sources. "
                + "The OsWL software license does not relicense third-party data. Retain supplied record-level credits and notices.");
        dataNotices.put("changes", hasOriginals
                ? "Exported records are selected from OsWL scan data. Attached OSV originals are preserved where their stored content matches the lookup assessment; other fields may be normalized, omitted or combined across sources."
                : "Exported records are selected and normalized from OsWL scan data; they are not original advisory documents. Fields may be omitted or combined from multiple sources.");
        ObjectNode githubNotice = dataNotices.putObject("githubAdvisoryDatabase");
        githubNotice.put("appliesTo", "GitHub Advisory Database material, where present; not every record with a GHSA alias.");
        githubNotice.put("attribution", "GitHub Advisory Database and contributors; retain any supplied creator attribution.");
        githubNotice.put("sourceUrl", "https://github.com/github/advisory-database");
        githubNotice.put("license", "CC-BY-4.0");
        githubNotice.put("licenseUrl", "https://creativecommons.org/licenses/by/4.0/");
        githubNotice.put("disclaimer", "No endorsement is implied. Licensed material is supplied without warranties; "
                + "see the license for its disclaimer and limitations. Linked external content is not covered by this notice.");
        Set<JsonNode> roundTripNotices = new LinkedHashSet<>(upstreamNotices);
        roundTripNotices.add(dataNotices);
        checkNoticeBudget(roundTripNotices);
        ObjectNode sources = meta.putObject("sources");
        putSourceMeta(sources, SOURCE_OSV, osvRecords, null);
        putSourceMeta(sources, SOURCE_UNRESOLVED, unresolvedRecords, null);
        putSourceMeta(sources, SOURCE_COCOAPODS_SPECS, specKeys.size(), null);
        snapshotMetaRepository.findById(SOURCE_COCOAPODS_SPECS).ifPresent(saved -> {
            ObjectNode source = (ObjectNode) sources.get(SOURCE_COCOAPODS_SPECS);
            if (saved.getSourceAsOf() != null) source.put("asOf", saved.getSourceAsOf().toString());
            if (saved.getOrigin() != null) source.put("origin", saved.getOrigin());
        });
        putSourceMeta(sources, SOURCE_DEPSDEV_VERSION, versionRecords, null);
        putSourceMeta(sources, SOURCE_DEPSDEV_ADVISORY, advisories.size(), null);
        putSourceMeta(sources, SOURCE_GITHUB_ADVISORY, githubAdvisoryRecords, null);
        putSourceMeta(sources, SOURCE_NVD, nvdRecords, null);
        putSourceMeta(sources, SOURCE_EPSS, epss.size(), null);
        putSourceMeta(sources, SOURCE_KEV, kev.size(), null);
        ObjectNode files = meta.putObject("files");
        putFileMeta(files, "osv.jsonl", osvContent, osvRecords);
        putFileMeta(files, "unresolved.jsonl", unresolved.toString(), unresolvedRecords);
        putFileMeta(files, "cocoapods-specs.jsonl", specsContent.toString(), specKeys.size());
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
                writeZipEntry(zos, "unresolved.jsonl", unresolved.toString());
                writeZipEntry(zos, "cocoapods-specs.jsonl", specsContent.toString());
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
        return appendVulnLines(target, lib, cves, false);
    }

    private int appendVulnLines(StringBuilder target, Library lib, List<Cve> cves, boolean osvKeys) {
        List<SnapshotVuln> vulns = cves.stream()
                .map(c -> new SnapshotVuln(c.getGhsaId(), c.getCveId(), c.getSummary(),
                        c.getFixVersion(), c.getCweId(),
                        c.getSeverity() != null ? c.getSeverity().name() : null,
                        c.getCvssScore(), c.getCvss3Vector(),
                        c.getMatchConfidence() != null ? c.getMatchConfidence().name() : null, c.getFixVersionConflictCandidates()))
                .toList();
        return appendSnapshotVulnLine(target, lib, vulns, osvKeys);
    }

    private String osvExportKey(Library lib) {
        return "COCOAPODS".equalsIgnoreCase(lib.getEcosystem()) && lib.getSourceRepoUrl() != null
                ? componentKey("SwiftURL", lib.getSourceRepoUrl(), lib.getVersion())
                : componentKey(lib.getEcosystem(), lib.getName(), lib.getVersion());
    }

    private List<SnapshotVuln> exportableOriginals(Library library, List<Cve> cves, List<SnapshotVuln> stored) {
        var assessment = library.getOsvFixAssessment();
        if (stored == null || assessment == null || assessment.advisoryRevisions().isEmpty()
                || !assessment.advisoryDigests().keySet().equals(assessment.advisoryRevisions().keySet())
                || library.getVulnerabilityLookupOutcomes() == null
                || !"RESOLVED".equals(library.getVulnerabilityLookupOutcomes().get("OSV"))) return null;
        if (cves.stream().anyMatch(c -> c.isFixVersionConflict()
                || !(assessment.findingIds().contains(c.getGhsaId() == null ? "" : c.getGhsaId())
                || assessment.findingIds().contains(c.getCveId() == null ? "" : c.getCveId())))) return null;
        Map<String, JsonNode> originals = new LinkedHashMap<>();
        for (SnapshotVuln record : stored) {
            JsonNode raw = record.osvAdvisory();
            if (raw == null || !record.fixVersionConflictCandidates().isEmpty()
                    || !Objects.equals(assessment.advisoryRevisions().get(record.osvId()), raw.path("modified").asText())
                    || !Objects.equals(assessment.advisoryDigests().get(record.osvId()), com.salkcoding.oswl.vdb.OsvOriginalDigest.of(raw))) return null;
            JsonNode previous = originals.putIfAbsent(record.osvId(), raw);
            if (previous != null && !previous.equals(raw)) return null;
        }
        if (!originals.keySet().equals(assessment.advisoryRevisions().keySet())) return null;
        List<SnapshotVuln> result = new ArrayList<>();
        originals.forEach((id, raw) -> {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(id);
            raw.path("aliases").forEach(alias -> { if (alias.isTextual()) aliases.add(alias.asText()); });
            Cve finding = cves.stream().filter(c -> aliases.contains(c.getGhsaId()) || aliases.contains(c.getCveId())).findFirst().orElse(null);
            result.add(new SnapshotVuln(id, finding == null ? null : finding.getCveId(), finding == null ? null : finding.getSummary(),
                    finding == null ? null : finding.getFixVersion(), finding == null ? null : finding.getCweId(),
                    finding == null || finding.getSeverity() == null ? null : finding.getSeverity().name(),
                    finding == null ? null : finding.getCvssScore(), finding == null ? null : finding.getCvss3Vector(),
                    finding == null || finding.getMatchConfidence() == null ? null : finding.getMatchConfidence().name(), Set.of(), raw));
        });
        return result;
    }

    private int appendSnapshotVulnLine(StringBuilder target, Library lib, List<SnapshotVuln> vulns, boolean osvKeys) {
        ObjectNode line = objectMapper.createObjectNode();
        line.put("ecosystem", lib.getEcosystem());
        line.put("name", lib.getName());
        if (osvKeys && "COCOAPODS".equalsIgnoreCase(lib.getEcosystem()) && lib.getSourceRepoUrl() != null) {
            line.put("ecosystem", "SwiftURL");
            line.put("name", lib.getSourceRepoUrl());
        }
        line.put("version", lib.getVersion());
        line.set("vulns", objectMapper.valueToTree(vulns));
        String payload = writeJson(line);
        SnapshotBundleStager.requireImportableLine(payload);
        target.append(payload).append('\n');
        return 1;
    }

    private static void putSourceMeta(ObjectNode sources, String source, int records, LocalDate asOf) {
        ObjectNode s = sources.putObject(source);
        s.put("records", records);
        if (asOf != null) s.put("asOf", asOf.toString());
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
