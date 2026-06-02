package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.domain.enums.Patchability;
import com.salkcoding.oswl.domain.enums.RiskLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds rich scan context strings for AI trend/posture prompts. */
public final class AiEnrichmentContextBuilder {

    private AiEnrichmentContextBuilder() {}

    public record PostureContext(
            int critical, int high, int medium, int low,
            int totalComponents, int patchableCount, int nonPatchableCount,
            int directCriticalHigh, String topIssues) {}

    public static PostureContext buildPosture(List<Library> libs, Map<Long, String> depInfoByLibId) {
        int critical = 0, high = 0, medium = 0, low = 0;
        int patchable = 0, nonPatchable = 0, directCriticalHigh = 0;

        List<String> topIssues = new ArrayList<>();

        for (Library lib : libs) {
            Patchability p = lib.computePatchability();
            if (p == Patchability.PATCHABLE) patchable++;
            else if (p == Patchability.NON_PATCHABLE) nonPatchable++;

            boolean direct = isDirect(depInfoByLibId.get(lib.getId()));
            RiskLevel highest = lib.highestSeverity();

            for (Cve cve : lib.getCves()) {
                RiskLevel sev = cve.getSeverity();
                if (sev == null || sev == RiskLevel.NONE) continue;
                switch (sev) {
                    case CRITICAL -> critical++;
                    case HIGH -> high++;
                    case MEDIUM -> medium++;
                    case LOW -> low++;
                    default -> { /* NONE */ }
                }
                if (direct && (sev == RiskLevel.CRITICAL || sev == RiskLevel.HIGH)) {
                    directCriticalHigh++;
                }
                if ((sev == RiskLevel.CRITICAL || sev == RiskLevel.HIGH) && topIssues.size() < 5) {
                    String id = cve.getCveId() != null ? cve.getCveId()
                            : (cve.getGhsaId() != null ? cve.getGhsaId() : "unknown");
                    topIssues.add("- " + sev.name() + " " + id + " in " + lib.getName() + " "
                            + nullSafe(lib.getVersion())
                            + (cve.getFixVersion() != null ? " (fix: " + cve.getFixVersion() + ")" : ""));
                }
            }
        }

        String top = topIssues.isEmpty() ? "- None flagged" : String.join("\n", topIssues);
        return new PostureContext(critical, high, medium, low, libs.size(),
                patchable, nonPatchable, directCriticalHigh, top);
    }

    public static String buildSecurityTrendDetails(List<Library> current, List<Library> previous,
                                                   Map<Long, String> depInfo, int secDelta) {
        SeverityCounts cur = countSeverities(current);
        SeverityCounts prev = previous.isEmpty()
                ? new SeverityCounts(0, 0, 0, 0)
                : countSeverities(previous);

        Set<String> prevKeys = cveKeys(previous);
        Set<String> currKeys = cveKeys(current);
        Set<String> newKeys = new HashSet<>(currKeys);
        newKeys.removeAll(prevKeys);
        Set<String> resolvedKeys = new HashSet<>(prevKeys);
        resolvedKeys.removeAll(currKeys);

        List<String> lines = new ArrayList<>();
        lines.add(String.format("- CVE count delta: %+d (Critical %d→%d, High %d→%d)",
                secDelta, prev.critical, cur.critical, prev.high, cur.high));
        lines.add(String.format("- New CVE instances: %d, Resolved: %d", newKeys.size(), resolvedKeys.size()));

        List<String> newCriticalHigh = findNewCriticalHigh(current, newKeys, depInfo);
        if (!newCriticalHigh.isEmpty()) {
            lines.add("- New Critical/High:");
            newCriticalHigh.stream().limit(3).forEach(l -> lines.add("  " + l));
        }

        return String.join("\n", lines);
    }

    public static String buildLicenseTrendDetails(List<Library> current, List<Library> previous, int licDelta) {
        Set<String> prevRisk = riskLibKeys(previous);
        Set<String> currRisk = riskLibKeys(current);
        Set<String> newRisk = new HashSet<>(currRisk);
        newRisk.removeAll(prevRisk);
        Set<String> cleared = new HashSet<>(prevRisk);
        cleared.removeAll(currRisk);

        List<String> lines = new ArrayList<>();
        lines.add(String.format("- License-risk component delta: %+d", licDelta));
        lines.add(String.format("- New license-risk components: %d, Cleared: %d", newRisk.size(), cleared.size()));

        if (!newRisk.isEmpty()) {
            lines.add("- Newly flagged:");
            newRisk.stream().limit(5).forEach(k -> lines.add("  - " + k));
        }
        return String.join("\n", lines);
    }

    public static String dependencyType(String dependencyInfo) {
        if (dependencyInfo == null || dependencyInfo.isBlank()) return "unknown";
        String lower = dependencyInfo.toLowerCase();
        if (lower.contains("direct") && lower.contains("transitive")) return "direct+transitive";
        if (lower.contains("direct")) return "direct";
        if (lower.contains("transitive")) return "transitive";
        return dependencyInfo.strip();
    }

    public static boolean isDirect(String dependencyInfo) {
        return dependencyInfo != null && dependencyInfo.toLowerCase().contains("direct");
    }

    public static Map<Long, String> depInfoByLibraryId(
            List<com.salkcoding.oswl.domain.entity.ScanComponent> components) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (var sc : components) {
            map.putIfAbsent(sc.getLibrary().getId(), sc.getDependencyInfo());
        }
        return map;
    }

    public static String nullSafe(String value) {
        return value != null ? value : "-";
    }

    public static String orDash(String value) {
        return value != null && !value.isBlank() ? value.strip() : "-";
    }

    private record SeverityCounts(int critical, int high, int medium, int low) {}

    private static SeverityCounts countSeverities(List<Library> libs) {
        int c = 0, h = 0, m = 0, l = 0;
        for (Library lib : libs) {
            for (Cve cve : lib.getCves()) {
                if (cve.getSeverity() == null) continue;
                switch (cve.getSeverity()) {
                    case CRITICAL -> c++;
                    case HIGH -> h++;
                    case MEDIUM -> m++;
                    case LOW -> l++;
                    default -> { /* NONE */ }
                }
            }
        }
        return new SeverityCounts(c, h, m, l);
    }

    private static Set<String> cveKeys(List<Library> libs) {
        Set<String> keys = new HashSet<>();
        for (Library lib : libs) {
            for (Cve cve : lib.getCves()) {
                String id = cve.getCveId() != null ? cve.getCveId()
                        : (cve.getGhsaId() != null ? cve.getGhsaId() : String.valueOf(cve.getId()));
                keys.add(lib.getName() + "|" + nullSafe(lib.getVersion()) + "|" + id);
            }
        }
        return keys;
    }

    private static List<String> findNewCriticalHigh(List<Library> libs, Set<String> newKeys,
                                                    Map<Long, String> depInfo) {
        List<CveRef> refs = new ArrayList<>();
        for (Library lib : libs) {
            for (Cve cve : lib.getCves()) {
                String id = cve.getCveId() != null ? cve.getCveId()
                        : (cve.getGhsaId() != null ? cve.getGhsaId() : String.valueOf(cve.getId()));
                String key = lib.getName() + "|" + nullSafe(lib.getVersion()) + "|" + id;
                if (!newKeys.contains(key)) continue;
                if (cve.getSeverity() != RiskLevel.CRITICAL && cve.getSeverity() != RiskLevel.HIGH) continue;
                refs.add(new CveRef(cve.getSeverity(), id, lib, depInfo.get(lib.getId())));
            }
        }
        refs.sort(Comparator.comparingInt(r -> r.severity.ordinal()));
        return refs.stream()
                .map(r -> r.severity.name() + " " + r.id + " in " + r.lib.getName() + " "
                        + nullSafe(r.lib.getVersion())
                        + " (" + dependencyType(r.depInfo) + ")")
                .collect(Collectors.toList());
    }

    private static Set<String> riskLibKeys(List<Library> libs) {
        Set<String> keys = new HashSet<>();
        for (Library lib : libs) {
            LicenseStatus ls = lib.getLicenseStatus();
            if (ls == LicenseStatus.RESTRICTED || ls == LicenseStatus.CAUTION || ls == LicenseStatus.UNKNOWN) {
                keys.add(lib.getName() + " " + nullSafe(lib.getVersion()) + " (" + ls.name() + ")");
            }
        }
        return keys;
    }

    private record CveRef(RiskLevel severity, String id, Library lib, String depInfo) {}
}
