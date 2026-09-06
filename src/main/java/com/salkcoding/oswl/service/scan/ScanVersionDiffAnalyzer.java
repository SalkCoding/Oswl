package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.dto.scan.VersionDiffComponent;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionDiffRowDto.ChangeType;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** Computes version-to-version component diff stats for AI insights and the Version Diff page. */
@Service
@RequiredArgsConstructor
public class ScanVersionDiffAnalyzer {

    private final ScanComponentRepository scanComponentRepository;

    public record DiffResult(
            int added, int removed, int updated, int newThreats,
            List<VersionDiffRowDto> rows, String threatDetails) {}

    public DiffResult compare(Long fromScanId, Long toScanId) {
        Map<String, List<VersionDiffComponent>> fromMap = buildMap(fromScanId);
        Map<String, List<VersionDiffComponent>> toMap   = buildMap(toScanId);

        List<VersionDiffRowDto> rows = new ArrayList<>();
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(fromMap.keySet());
        allNames.addAll(toMap.keySet());

        int added = 0, removed = 0, updated = 0, newThreat = 0;

        for (String name : allNames) {
            // Compare per (name, version) so a library present in several versions
            // (e.g. diamond dependencies) is never collapsed into a single row.
            Map<String, VersionDiffComponent> fromByVersion = byVersion(fromMap.get(name));
            Map<String, VersionDiffComponent> toByVersion   = byVersion(toMap.get(name));

            Set<String> allVersions = new LinkedHashSet<>();
            allVersions.addAll(fromByVersion.keySet());
            allVersions.addAll(toByVersion.keySet());

            List<String> removedVersions = new ArrayList<>();
            List<String> addedVersions   = new ArrayList<>();
            for (String version : allVersions) {
                VersionDiffComponent fromComp = fromByVersion.get(version);
                VersionDiffComponent toComp   = toByVersion.get(version);
                if (fromComp != null && toComp != null) {
                    // Same name+version in both scans — only a severity change is notable.
                    if (isNewThreat(fromComp, toComp)) {
                        rows.add(diffRow(fromComp, toComp, ChangeType.NEW_THREAT));
                        newThreat++;
                    }
                } else if (fromComp != null) {
                    removedVersions.add(version);
                } else {
                    addedVersions.add(version);
                }
            }

            // One from-only version replaced by one to-only version → version change.
            if (removedVersions.size() == 1 && addedVersions.size() == 1) {
                VersionDiffComponent fromComp = fromByVersion.get(removedVersions.get(0));
                VersionDiffComponent toComp   = toByVersion.get(addedVersions.get(0));
                if (isNewThreat(fromComp, toComp)) {
                    rows.add(diffRow(fromComp, toComp, ChangeType.NEW_THREAT));
                    newThreat++;
                } else {
                    rows.add(diffRow(fromComp, toComp, ChangeType.UPDATED));
                    updated++;
                }
                continue;
            }

            for (String version : removedVersions) {
                rows.add(diffRow(fromByVersion.get(version), null, ChangeType.REMOVED));
                removed++;
            }
            for (String version : addedVersions) {
                VersionDiffComponent toComp = toByVersion.get(version);
                if (toComp.highestSeverity() != RiskLevel.NONE) {
                    rows.add(diffRow(null, toComp, ChangeType.NEW_THREAT));
                    newThreat++;
                } else {
                    rows.add(diffRow(null, toComp, ChangeType.ADDED));
                    added++;
                }
            }
        }

        return new DiffResult(added, removed, updated, newThreat, rows, buildThreatDetails(rows));
    }

    /** True when the to-scan component is a new or more severe threat than the from-scan one. */
    private static boolean isNewThreat(VersionDiffComponent fromComp, VersionDiffComponent toComp) {
        RiskLevel fromRisk = fromComp.highestSeverity();
        RiskLevel toRisk   = toComp.highestSeverity();
        return (toRisk != RiskLevel.NONE)
                && (fromRisk == RiskLevel.NONE || toRisk.ordinal() < fromRisk.ordinal());
    }

    private static VersionDiffRowDto diffRow(VersionDiffComponent fromComp, VersionDiffComponent toComp, ChangeType type) {
        return VersionDiffRowDto.builder()
                .fromName(fromComp != null ? fromComp.getName() : null)
                .fromVersion(fromComp != null ? fromComp.getVersion() : null)
                .fromRiskLevel(fromComp != null ? fromComp.highestSeverity().name() : null)
                .toName(toComp != null ? toComp.getName() : null)
                .toVersion(toComp != null ? toComp.getVersion() : null)
                .toRiskLevel(toComp != null ? toComp.highestSeverity().name() : null)
                .changeType(type)
                .build();
    }

    private static String buildThreatDetails(List<VersionDiffRowDto> rows) {
        StringBuilder threatDetails = new StringBuilder();
        rows.stream()
                .filter(r -> r.getChangeType() == ChangeType.NEW_THREAT)
                .limit(8)
                .forEach(r -> {
                    String name = r.getToName() != null ? r.getToName() : r.getFromName();
                    String ver  = r.getToVersion() != null ? r.getToVersion() : r.getFromVersion();
                    String risk = r.getToRiskLevel() != null ? r.getToRiskLevel() : r.getFromRiskLevel();
                    threatDetails.append("- NEW_THREAT: ").append(name).append(" ").append(ver)
                            .append(" (").append(risk != null ? risk : "unknown").append(")\n");
                });
        rows.stream()
                .filter(r -> r.getChangeType() == ChangeType.ADDED)
                .limit(3)
                .forEach(r -> threatDetails.append("- ADDED: ").append(r.getToName())
                        .append(" ").append(r.getToVersion()).append("\n"));
        if (threatDetails.isEmpty()) {
            threatDetails.append("- No notable threat-level component changes");
        }
        return threatDetails.toString().strip();
    }

    private Map<String, List<VersionDiffComponent>> buildMap(Long scanId) {
        return scanComponentRepository.findVersionDiffComponents(scanId).stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        VersionDiffComponent::getName,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
    }

    /** Components of one library name keyed by version; the first row wins on exact duplicates. */
    private static Map<String, VersionDiffComponent> byVersion(List<VersionDiffComponent> components) {
        if (components == null) return Map.of();
        Map<String, VersionDiffComponent> map = new LinkedHashMap<>();
        for (VersionDiffComponent sc : components) {
            map.putIfAbsent(sc.getVersion() != null ? sc.getVersion() : "", sc);
        }
        return map;
    }
}
