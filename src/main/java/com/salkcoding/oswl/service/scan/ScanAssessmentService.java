package com.salkcoding.oswl.service.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.dto.scan.ScanAssessment;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScanAssessmentService {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ScanResultRepository scans;

    /** First published data phase wins. Later entity saves cannot erase or replace it. */
    @Transactional
    public void capture(Long scanId, List<ScanComponent> components) {
        var distinct = new java.util.LinkedHashMap<Long,Library>();
        for (var component : components) distinct.putIfAbsent(component.getLibrary().getId(),component.getLibrary());
        var assessment = new ScanAssessment(1, Instant.now().toString(),
                distinct.values().stream().map(ScanAssessmentService::fromLibrary).toList());
        try {
            scans.pinAssessmentIfAbsent(scanId, JSON.writeValueAsString(assessment));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot preserve scan assessment", e);
        }
    }

    public static ScanAssessment read(String json) {
        try {
            return JSON.readValue(json, ScanAssessment.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read preserved scan assessment", e);
        }
    }

    /** Also projects explicitly legacy live data; callers must not persist it as historic evidence. */
    public static ScanAssessment.LibraryAssessment fromLibrary(Library library) {
        return new ScanAssessment.LibraryAssessment(library.getId(),library.getName(),library.getVersion(),
                library.getEcosystem(),library.getLicenseName(),library.getLicenseExpressionRaw(),library.getLicenseStatus(),
                library.getVulnerabilityLookupAt() == null ? null : library.getVulnerabilityLookupAt().toString(),
                library.getFetchedAt() == null ? null : library.getFetchedAt().toString(),library.isMalicious(),
                library.getVulnerabilityLookupOutcomes(),library.getOsvFixAssessment(),library.getCves().stream()
                .map(c -> new ScanAssessment.Finding(c.getCveId(),c.getGhsaId(),c.getSeverity(),c.getCvssScore(),
                        c.getCvss3Vector(),c.getTitle(),c.getSummary(),c.getCweId(),c.getFixVersion(),
                        c.getFixVersionConflictCandidates(),c.getEpssScore(),c.getKevListed(),c.getSources(),
                        c.isSeverityConflict(),c.getMatchConfidence(),c.getNvdApplicability())).toList(),library.hasValidLookupTimes());
    }
}
