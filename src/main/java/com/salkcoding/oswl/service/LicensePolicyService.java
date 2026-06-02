package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.LicensePolicyEntryDto;
import com.salkcoding.oswl.repository.LicensePolicyRepository;
import com.salkcoding.oswl.aop.Auditable;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the license classification policy.
 *
 * Built-in defaults are seeded to the database on first startup (builtIn = true).
 * The in-memory map is loaded at startup and updated whenever the policy changes.
 *
 * SPDX expressions with "OR" / "AND" / "WITH" are handled conservatively:
 *   OR  → best (least restrictive) status among the operands
 *   AND → worst (most restrictive) status among the operands
 *   WITH → treated as a single identifier (looked up verbatim, then falls back to base)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicensePolicyService {

    private final LicensePolicyRepository licensePolicyRepository;

    /** In-memory cache: SPDX ID (upper-case) → LicenseStatus */
    private final Map<String, LicenseStatus> policyCache = new ConcurrentHashMap<>();

    // ── Default policy lists ─────────────────────────────────────────────

    private static final List<String> DEFAULT_OK = List.of(
            "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause",
            "ISC", "Unlicense", "0BSD", "CC0-1.0", "Zlib", "BSL-1.0", "MIT-0");

    private static final List<String> DEFAULT_WARN = List.of(
            "LGPL-2.0", "LGPL-2.1", "LGPL-3.0",
            "MPL-1.0", "MPL-1.1", "MPL-2.0",
            "CDDL-1.0", "EPL-1.0", "EPL-2.0",
            "EUPL-1.1", "EUPL-1.2", "APSL-2.0");

    private static final List<String> DEFAULT_VIOLATION = List.of(
            "GPL-2.0", "GPL-3.0", "AGPL-1.0", "AGPL-3.0",
            "SSPL-1.0", "BUSL-1.1",
            "CC-BY-NC-4.0", "CC-BY-NC-SA-4.0");

    // ── Lifecycle ────────────────────────────────────────────────────────

    @PostConstruct
    @Transactional
    public void init() {
        seedDefaults();
        refreshCache();
    }

    /** Reloads the in-memory map from the database. */
    public void refreshCache() {
        policyCache.clear();
        licensePolicyRepository.findAll()
                .forEach(entry -> policyCache.put(entry.getSpdxId().toUpperCase(), entry.getStatus()));
        log.info("[LicensePolicyService] Cache loaded with {} entries", policyCache.size());
    }

    // ── Classification ────────────────────────────────────────────────────

    /**
     * Classifies a raw license string (SPDX expression or identifier).
     * Returns UNKNOWN if the identifier is null/blank or not found in the policy.
     */
    public LicenseStatus classify(String spdxExpression) {
        if (spdxExpression == null || spdxExpression.isBlank()) return LicenseStatus.UNKNOWN;

        String expr = spdxExpression.trim();

        // Handle OR expressions: use the best (least restrictive) result
        if (expr.contains(" OR ")) {
            return List.of(expr.split(" OR "))
                    .stream()
                    .map(this::classify)
                    .min(LicensePolicyService::compareByRestrictiveness)
                    .orElse(LicenseStatus.UNKNOWN);
        }

        // Handle AND expressions: use the worst (most restrictive) result.
        // If any part is UNKNOWN (e.g. "non-standard"), the whole expression is UNKNOWN.
        if (expr.contains(" AND ")) {
            List<LicenseStatus> parts = List.of(expr.split(" AND "))
                    .stream()
                    .map(this::classify)
                    .toList();
            if (parts.contains(LicenseStatus.UNKNOWN)) return LicenseStatus.UNKNOWN;
            return parts.stream()
                    .max(LicensePolicyService::compareByRestrictiveness)
                    .orElse(LicenseStatus.UNKNOWN);
        }

        // Strip WITH modifier, look up the base identifier
        String baseId = expr.contains(" WITH ") ? expr.split(" WITH ")[0].trim() : expr;

        // Strip parentheses
        baseId = baseId.replace("(", "").replace(")", "").trim();

        LicenseStatus status = policyCache.get(baseId.toUpperCase());
        return status != null ? status : LicenseStatus.UNKNOWN;
    }

    // ── Policy management ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LicensePolicyEntryDto> findAllEntries() {
        return licensePolicyRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    @Auditable(action = "LICENSE_POLICY.UPDATE", targetType = "LICENSE_POLICY",
               targetIdExpr = "#spdxId", targetNameExpr = "#spdxId",
               detailExpr = "#newStatus.name()")
    public LicensePolicyEntryDto updateEntry(String spdxId, LicenseStatus newStatus) {
        LicensePolicyEntry entry = licensePolicyRepository.findBySpdxId(spdxId)
                .orElseGet(() -> {
                    LicensePolicyEntry ne = new LicensePolicyEntry();
                    ne.setSpdxId(spdxId.trim());
                    ne.setBuiltIn(false);
                    return ne;
                });
        entry.updateStatus(newStatus, null);
        LicensePolicyEntry saved = licensePolicyRepository.save(entry);
        policyCache.put(saved.getSpdxId().toUpperCase(), newStatus);
        return toDto(saved);
    }

    private LicensePolicyEntryDto toDto(LicensePolicyEntry entry) {
        return LicensePolicyEntryDto.builder()
                .id(entry.getId())
                .spdxId(entry.getSpdxId())
                .status(entry.getStatus())
                .reason(entry.getReason())
                .builtIn(entry.isBuiltIn())
                .build();
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private void seedDefaults() {
        if (licensePolicyRepository.count() > 0) return;

        DEFAULT_OK.forEach(id -> saveDefault(id, LicenseStatus.PERMITTED, "Permissive open-source license"));
        DEFAULT_WARN.forEach(id -> saveDefault(id, LicenseStatus.CAUTION, "Copyleft with limited obligations"));
        DEFAULT_VIOLATION.forEach(id -> saveDefault(id, LicenseStatus.RESTRICTED, "Strong copyleft or commercial restriction"));
        log.info("[LicensePolicyService] Seeded {} default license policy entries",
                DEFAULT_OK.size() + DEFAULT_WARN.size() + DEFAULT_VIOLATION.size());
    }

    private void saveDefault(String spdxId, LicenseStatus status, String reason) {
        LicensePolicyEntry entry = new LicensePolicyEntry();
        entry.setSpdxId(spdxId);
        entry.setStatus(status);
        entry.setReason(reason);
        entry.setBuiltIn(true);
        licensePolicyRepository.save(entry);
    }

    /**
     * Ordering: VIOLATION > WARN > OK > UNKNOWN
     * (higher = more restrictive)
     */
    private static int compareByRestrictiveness(LicenseStatus a, LicenseStatus b) {
        return Integer.compare(restrictiveRank(a), restrictiveRank(b));
    }

    private static int restrictiveRank(LicenseStatus s) {
        return switch (s) {
            case RESTRICTED -> 3;
            case CAUTION      -> 2;
            case PERMITTED        -> 1;
            case UNKNOWN   -> 0;
        };
    }
}
