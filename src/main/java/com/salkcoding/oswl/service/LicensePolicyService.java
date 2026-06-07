package com.salkcoding.oswl.service;



import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;

import com.salkcoding.oswl.domain.enums.LicenseStatus;

import com.salkcoding.oswl.dto.LicensePolicyEntryDto;

import com.salkcoding.oswl.dto.LicensePolicyPageResponse;

import com.salkcoding.oswl.license.SpdxLicenseClassifier;

import com.salkcoding.oswl.license.SpdxLicenseRegistry;

import com.salkcoding.oswl.repository.LicensePolicyRepository;

import com.salkcoding.oswl.aop.Auditable;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.Page;

import org.springframework.data.domain.PageRequest;

import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;



import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

import java.util.stream.Collectors;



/**

 * Manages the license classification policy.

 *

 * Built-in defaults are seeded from the bundled SPDX license list on first startup.

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

    private final SpdxLicenseRegistry spdxLicenseRegistry;



    /** In-memory cache: SPDX ID (upper-case) → LicenseStatus */

    private final Map<String, LicenseStatus> policyCache = new ConcurrentHashMap<>();

    private final Map<String, String> reasonCache = new ConcurrentHashMap<>();



    // ── Lifecycle ────────────────────────────────────────────────────────



    @PostConstruct

    @Transactional

    public void init() {

        ensureBuiltInDefaults();

        refreshCache();

    }



    /** Reloads the in-memory map from the database. */

    public void refreshCache() {

        policyCache.clear();

        reasonCache.clear();

        licensePolicyRepository.findAll()

                .forEach(entry -> {

                    String key = entry.getSpdxId().toUpperCase();

                    policyCache.put(key, entry.getStatus());

                    if (entry.getReason() != null && !entry.getReason().isBlank()) {

                        reasonCache.put(key, entry.getReason().strip());

                    }

                });

        log.info("[LicensePolicyService] Cache loaded with {} entries", policyCache.size());

    }



    /** Organization policy rationale for AI prompts (best-effort for SPDX expressions). */

    public String policyReason(String spdxExpression) {

        if (spdxExpression == null || spdxExpression.isBlank()) {

            return "No license identifier";

        }

        String baseId = extractBaseSpdxId(spdxExpression.trim());

        String reason = reasonCache.get(baseId.toUpperCase());

        if (reason != null) return reason;

        return switch (classify(spdxExpression)) {

            case RESTRICTED -> "Restricted by organization policy (strong copyleft or commercial limits)";

            case CAUTION -> "Requires legal review (weak copyleft or notice obligations)";

            case PERMITTED -> "Permitted by organization policy";

            case UNKNOWN -> "Unknown license — requires manual classification";

        };

    }



    private static String extractBaseSpdxId(String expr) {

        if (expr.contains(" OR ")) expr = expr.split(" OR ")[0].trim();

        if (expr.contains(" AND ")) expr = expr.split(" AND ")[0].trim();

        if (expr.contains(" WITH ")) expr = expr.split(" WITH ")[0].trim();

        return expr.replace("(", "").replace(")", "").trim();

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



    private static final int MAX_PAGE_SIZE = 100;

    private static final Sort SPDX_SORT = Sort.by("spdxId").ascending();



    @Transactional(readOnly = true)

    public LicensePolicyPageResponse findEntries(String query, int page, int size) {

        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        int safePage = Math.max(page, 0);



        if (query != null && !query.isBlank()) {

            return searchEntries(query.strip(), safePage, safeSize);

        }



        Page<LicensePolicyEntry> result = licensePolicyRepository.findAll(

                PageRequest.of(safePage, safeSize, SPDX_SORT));

        return toPageResponse(result, safePage, safeSize);

    }



    private LicensePolicyPageResponse searchEntries(String query, int page, int size) {

        String q = query.toLowerCase();

        List<LicensePolicyEntry> matches = licensePolicyRepository.findAll(SPDX_SORT).stream()

                .filter(entry -> matchesQuery(entry, q))

                .toList();



        int from = page * size;

        if (from >= matches.size()) {

            return LicensePolicyPageResponse.builder()

                    .items(List.of())

                    .page(page)

                    .size(size)

                    .total(matches.size())

                    .hasMore(false)

                    .build();

        }

        int to = Math.min(from + size, matches.size());

        List<LicensePolicyEntryDto> items = matches.subList(from, to).stream()

                .map(this::toDto)

                .toList();

        return LicensePolicyPageResponse.builder()

                .items(items)

                .page(page)

                .size(size)

                .total(matches.size())

                .hasMore(to < matches.size())

                .build();

    }



    private boolean matchesQuery(LicensePolicyEntry entry, String qLower) {

        if (entry.getSpdxId().toLowerCase().contains(qLower)) {

            return true;

        }

        return spdxLicenseRegistry.displayName(entry.getSpdxId()).toLowerCase().contains(qLower);

    }



    private LicensePolicyPageResponse toPageResponse(Page<LicensePolicyEntry> page, int pageNum, int size) {

        List<LicensePolicyEntryDto> items = page.getContent().stream()

                .map(this::toDto)

                .toList();

        return LicensePolicyPageResponse.builder()

                .items(items)

                .page(pageNum)

                .size(size)

                .total(page.getTotalElements())

                .hasMore(page.hasNext())

                .build();

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

                .displayName(spdxLicenseRegistry.displayName(entry.getSpdxId()))

                .spdxUrl(spdxLicenseRegistry.referenceUrl(entry.getSpdxId()))

                .status(entry.getStatus())

                .reason(entry.getReason())

                .builtIn(entry.isBuiltIn())

                .build();

    }



    // ── Internals ─────────────────────────────────────────────────────────



    /**

     * Seeds missing built-in entries from the SPDX list (first install and upgrades).

     * Existing rows are never overwritten.

     */

    private void ensureBuiltInDefaults() {

        Set<String> existing = licensePolicyRepository.findAll().stream()

                .map(e -> e.getSpdxId().toUpperCase())

                .collect(Collectors.toSet());



        int added = 0;

        for (SpdxLicenseRegistry.SpdxLicenseInfo license : spdxLicenseRegistry.allActive()) {

            if (existing.contains(license.id().toUpperCase())) {

                continue;

            }

            LicenseStatus status = SpdxLicenseClassifier.classify(license.id(), license.osiApproved());

            saveDefault(license.id(), status, SpdxLicenseClassifier.defaultReason(status));

            added++;

        }

        if (added > 0) {

            log.info("[LicensePolicyService] Added {} SPDX license policy entries", added);

        }

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


