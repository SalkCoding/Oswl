package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ScanComponent;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.VersionDiffRowDto;
import com.salkcoding.oswl.dto.VersionDiffRowDto.ChangeType;
import com.salkcoding.oswl.repository.ScanComponentRepository;
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
        Map<String, ScanComponent> fromMap = buildMap(fromScanId);
        Map<String, ScanComponent> toMap   = buildMap(toScanId);

        List<VersionDiffRowDto> rows = new ArrayList<>();
        Set<String> allNames = new LinkedHashSet<>();
        allNames.addAll(fromMap.keySet());
        allNames.addAll(toMap.keySet());

        int added = 0, removed = 0, updated = 0, newThreat = 0;

        for (String name : allNames) {
            ScanComponent fromComp = fromMap.get(name);
            ScanComponent toComp   = toMap.get(name);

            ChangeType type;
            if (fromComp == null) {
                RiskLevel toRisk = toComp.getLibrary().highestSeverity();
                if (toRisk != RiskLevel.NONE) {
                    type = ChangeType.NEW_THREAT;
                    newThreat++;
                } else {
                    type = ChangeType.ADDED;
                    added++;
                }
            } else if (toComp == null) {
                type = ChangeType.REMOVED;
                removed++;
            } else {
                boolean versionChanged = !Objects.equals(fromComp.getVersion(), toComp.getVersion());
                RiskLevel fromRisk = fromComp.getLibrary().highestSeverity();
                RiskLevel toRisk   = toComp.getLibrary().highestSeverity();
                boolean isNewThreat = (toRisk != RiskLevel.NONE)
                        && (fromRisk == RiskLevel.NONE || toRisk.ordinal() < fromRisk.ordinal());
                if (isNewThreat) {
                    type = ChangeType.NEW_THREAT;
                    newThreat++;
                } else if (versionChanged) {
                    type = ChangeType.UPDATED;
                    updated++;
                } else {
                    continue;
                }
            }

            rows.add(VersionDiffRowDto.builder()
                    .fromName(fromComp != null ? fromComp.getName() : null)
                    .fromVersion(fromComp != null ? fromComp.getVersion() : null)
                    .fromRiskLevel(fromComp != null ? fromComp.getLibrary().highestSeverity().name() : null)
                    .toName(toComp != null ? toComp.getName() : null)
                    .toVersion(toComp != null ? toComp.getVersion() : null)
                    .toRiskLevel(toComp != null ? toComp.getLibrary().highestSeverity().name() : null)
                    .changeType(type)
                    .build());
        }

        return new DiffResult(added, removed, updated, newThreat, rows, buildThreatDetails(rows));
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

    private Map<String, ScanComponent> buildMap(Long scanId) {
        return scanComponentRepository.findByScanResultId(scanId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        sc -> sc.getLibrary().getName(),
                        sc -> sc,
                        (a, b) -> a));
    }
}
