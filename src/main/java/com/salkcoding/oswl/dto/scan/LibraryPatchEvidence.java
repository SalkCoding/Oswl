package com.salkcoding.oswl.dto.scan;

import com.salkcoding.oswl.domain.enums.RiskLevel;
import java.time.LocalDateTime;
import java.util.Map;

/** Scalar evidence, one row per finding (or a null finding for an empty library). */
public record LibraryPatchEvidence(Long libraryId, LocalDateTime fetchedAt, LocalDateTime lookupAt,
        Map<String, String> outcomes, String deprecated, Boolean latest, Long findingId,
        RiskLevel severity, String fixVersion, Long candidateCount) {}
