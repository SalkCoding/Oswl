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
    }

    public record LibraryAssessment(Long libraryId, String name, String version, String ecosystem,
                                    String licenseName, List<String> licenseExpressions, LicenseStatus licenseStatus,
                                    String vulnerabilityLookupAt, String fetchedAt, Boolean malicious,
                                    Map<String,String> lookupOutcomes, Library.OsvFixAssessment osvFixAssessment,
                                    List<Finding> findings) {
        public LibraryAssessment {
            licenseExpressions = licenseExpressions == null ? null : List.copyOf(licenseExpressions);
            licenseStatus = licenseStatus == null ? LicenseStatus.UNKNOWN : licenseStatus;
            lookupOutcomes = lookupOutcomes == null ? Map.of() : Map.copyOf(lookupOutcomes);
            findings = List.copyOf(findings);
        }
        public boolean lookupComplete() {
            return fetchedAt != null && malicious != null && lookupOutcomes.containsValue("RESOLVED")
                    && !lookupOutcomes.containsValue("UNAVAILABLE");
        }
    }

    public record Finding(String cveId, String ghsaId, RiskLevel severity, Double cvssScore,
                          String cvss3Vector, String title, String summary, String cweId,
                          String fixVersion, Set<String> fixVersionConflictCandidates,
                          Double epssScore, Boolean kevListed, Set<CveSource> sources,
                          boolean severityConflict, MatchConfidence matchConfidence) {
        public Finding {
            fixVersionConflictCandidates = fixVersionConflictCandidates == null ? Set.of() : Set.copyOf(fixVersionConflictCandidates);
            sources = sources == null ? Set.of() : Set.copyOf(sources);
            if (!fixVersionConflictCandidates.isEmpty()) fixVersion = null;
        }
        public boolean requiresCpeReview() {
            boolean packageEvidence = sources.contains(CveSource.OSV) || sources.contains(CveSource.DEPS_DEV)
                    || sources.contains(CveSource.GITHUB_ADVISORY);
            return !packageEvidence && (sources.contains(CveSource.NVD) || sources.contains(CveSource.CPE)
                    || matchConfidence != null);
        }
    }
}
