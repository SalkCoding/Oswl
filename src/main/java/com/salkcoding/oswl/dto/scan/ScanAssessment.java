package com.salkcoding.oswl.dto.scan;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Data-phase evidence, separate from mutable triage and subsequent AI commentary. */
public record ScanAssessment(int formatVersion, String capturedAt, List<LibraryAssessment> libraries) {
    public ScanAssessment {
        if (formatVersion != 1) throw new IllegalArgumentException("Unsupported scan assessment format");
        libraries = List.copyOf(libraries);
        var identities = new java.util.HashSet<Long>();
        for (var library : libraries) {
            if (!identities.add(library.libraryId())) {
                throw new IllegalArgumentException("Duplicate library identity in preserved scan assessment");
            }
        }
    }

    public record LibraryAssessment(Long libraryId, String name, String version, String ecosystem,
                                    String licenseName, List<String> licenseExpressions, LicenseStatus licenseStatus,
                                    String vulnerabilityLookupAt, String fetchedAt, Boolean malicious,
                                    Map<String,String> lookupOutcomes, Library.OsvFixAssessment osvFixAssessment,
                                    List<Finding> findings, Boolean lookupTimesVerified) {
        public LibraryAssessment {
            licenseExpressions = licenseExpressions == null ? null : List.copyOf(licenseExpressions);
            licenseStatus = licenseStatus == null ? LicenseStatus.UNKNOWN : licenseStatus;
            lookupOutcomes = lookupOutcomes == null ? Map.of() : Map.copyOf(lookupOutcomes);
            findings = List.copyOf(findings);
        }
        public boolean lookupComplete() {
            return Boolean.TRUE.equals(lookupTimesVerified) && fetchedAt != null && vulnerabilityLookupAt != null
                    && malicious != null && com.salkcoding.oswl.util.VulnerabilityLookupCoverage.isComplete(lookupOutcomes);
        }
    }

    public record Finding(String cveId, String ghsaId, RiskLevel severity, Double cvssScore,
                          String cvss3Vector, String title, String summary, String cweId,
                          String fixVersion, Set<String> fixVersionConflictCandidates,
                          Double epssScore, Boolean kevListed, Set<CveSource> sources,
                          boolean severityConflict, MatchConfidence matchConfidence, String nvdApplicability) {
        public Finding {
            fixVersionConflictCandidates = fixVersionConflictCandidates == null ? Set.of() : Set.copyOf(fixVersionConflictCandidates);
            sources = sources == null ? Set.of() : Set.copyOf(sources);
            if (!fixVersionConflictCandidates.isEmpty()) fixVersion = null;
        }
        public boolean requiresCpeReview() {
            return com.salkcoding.oswl.util.VulnerabilityMatchEvidence.requiresCpeReview(sources, matchConfidence);
        }
    }
}
