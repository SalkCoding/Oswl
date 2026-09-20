package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.CpeNameMapper;
import com.salkcoding.oswl.client.NvdClient;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds per-component {@link SnapshotVuln} maps from the NVD 2.0 REST API using CPE-based
 * lookups. Only components on CPE-applicable ecosystems ({@code CONAN}, {@code VCPKG},
 * {@code SYSTEM}) are queried — package-manager coordinates are covered by OSV/deps.dev/GitHub
 * Advisory. Matches carry the confidence grade returned by {@link CpeNameMapper} so the UI can
 * flag low-confidence CPE hits for manual review.
 */
final class NvdSource {

    private final ObjectMapper mapper;

    NvdSource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Result(Map<String, List<SnapshotVuln>> vulnsByComponentKey, java.util.Set<String> unresolvedKeys) {}

    Result fetch(List<WantedComponent> wanted, String apiKey) {
        NvdClient client = new NvdClient(
                null, false, apiKey, Duration.ofSeconds(5), Duration.ofSeconds(20));
        Map<String, List<SnapshotVuln>> result = new LinkedHashMap<>();
        java.util.Set<String> unresolvedKeys = new java.util.LinkedHashSet<>();
        int done = 0;
        for (WantedComponent w : wanted) {
            done++;
            if (done % 50 == 0) {
                System.err.println("[oswl-vdb] nvd: " + done + "/" + wanted.size());
            }
            String key = AirgappedSnapshotService.componentKey(w.ecosystem(), w.name(), w.version());
            if (key == null) throw new IllegalArgumentException("Missing NVD wanted identity cannot be recorded as unresolved");
            if (!isCpeEcosystem(w.ecosystem())) {
                unresolvedKeys.add(key);
                continue;
            }
            try {
                List<CpeNameMapper.CpeCandidate> candidates = CpeNameMapper.infer(w.name(), w.version());
                List<SnapshotVuln> vulns = new ArrayList<>();
                for (CpeNameMapper.CpeCandidate c : candidates) {
                    String cpeName = String.format(Locale.ROOT, "cpe:2.3:a:%s:%s:%s:*:*:*:*:*:*:*",
                            c.vendor(), c.product(), c.version());
                    List<NvdClient.NvdCve> cves;
                    try {
                        cves = client.findByCpeName(cpeName, c.confidence());
                    } catch (NvdClient.IncompleteLookupException e) {
                        unresolvedKeys.add(key);
                        cves = e.findings();
                    } catch (Exception e) {
                        unresolvedKeys.add(key);
                        System.err.println("[oswl-vdb] nvd candidate lookup failed for " + w.name() + "@" + w.version()
                                + ": " + e.getMessage());
                        continue;
                    }
                    for (NvdClient.NvdCve nvd : cves) {
                        vulns.add(toSnapshotVuln(nvd));
                    }
                }
                if (!candidates.isEmpty()) {
                    result.put(key, vulns);
                } else {
                    unresolvedKeys.add(key);
                }
            } catch (Exception e) {
                unresolvedKeys.add(key);
                System.err.println("[oswl-vdb] nvd lookup failed for " + w.name() + "@" + w.version()
                        + ": " + e.getMessage());
            }
        }
        System.err.println("[oswl-vdb] nvd: " + result.size() + " component(s) with retained lookup results");
        return new Result(result, java.util.Set.copyOf(unresolvedKeys));
    }

    private static SnapshotVuln toSnapshotVuln(NvdClient.NvdCve nvd) {
        return new SnapshotVuln(
                null, // OSV-style id — NVD results are CVE-only
                nvd.cveId(),
                nvd.description(),
                null, // NVD CPE lookup does not provide a fix version
                null, // CWE is not extracted in this path
                severityName(nvd.severity()),
                nvd.cvssScore(),
                nvd.cvss3Vector(),
                nvd.matchConfidence() != null ? nvd.matchConfidence().name() : null,
                java.util.Set.of(), null, nvd.nvdApplicability()
        );
    }

    private static String severityName(RiskLevel severity) {
        return severity != null && severity != RiskLevel.NONE ? severity.name() : null;
    }

    private static boolean isCpeEcosystem(String ecosystem) {
        if (ecosystem == null) return false;
        return switch (ecosystem.strip().toUpperCase(Locale.ROOT)) {
            case "CONAN", "VCPKG", "SYSTEM" -> true;
            default -> false;
        };
    }
}
