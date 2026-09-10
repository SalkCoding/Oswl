package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Chooses source fixed events for an installed package, never a registry's latest release. */
public final class OsvFixVersionSelector {
    public record Selection(String version, String reason) { }

    private OsvFixVersionSelector() { }

    public static Selection select(JsonNode advisory, String ecosystem, String name, String installed) {
        return selectAcrossAdvisories(java.util.Collections.singletonList(advisory), ecosystem, name, installed);
    }

    /**
     * Selects a source fixed event excluded by every supplied advisory's affected data.
     * Callers must supply the complete, current evidence set for the package; this method
     * cannot establish source coverage or freshness from a collection of JSON documents.
     */
    public static Selection selectAcrossAdvisories(List<JsonNode> advisories, String ecosystem, String name, String installed) {
        if (ecosystem == null || name == null || installed == null || installed.isBlank()) return unavailable("MISSING_IDENTITY");
        if (advisories == null || advisories.isEmpty()) return unavailable("NO_ADVISORY_EVIDENCE");
        var combined = JsonNodeFactory.instance.objectNode();
        var affected = combined.putArray("affected");
        try {
            for (JsonNode advisory : advisories) {
                if (advisory == null || !advisory.isObject() || !advisory.path("affected").isArray()) return unavailable("MALFORMED_ADVISORY");
                OsvWithdrawal withdrawal = OsvWithdrawal.from(advisory);
                if (withdrawal == OsvWithdrawal.WITHDRAWN) return unavailable("WITHDRAWN");
                if (withdrawal == OsvWithdrawal.UNKNOWN) return unavailable("MALFORMED_WITHDRAWAL");
                boolean matched = false;
                for (JsonNode entry : advisory.path("affected")) {
                    JsonNode pkg = entry.path("package");
                    if (ecosystem.equals(pkg.path("ecosystem").asText())
                            && AdvisoryPackageNames.canonical(ecosystem, name).equals(
                            AdvisoryPackageNames.canonical(ecosystem, pkg.path("name").asText()))) {
                        affected.add(entry);
                        matched = true;
                    }
                }
                if (!matched) return unavailable("NO_MATCHING_PACKAGE");
            }
        } catch (IllegalArgumentException unsupported) {
            return unavailable("UNSUPPORTED_VERSION");
        }
        return selectCombined(combined, ecosystem, name, installed);
    }

    private static Selection selectCombined(JsonNode advisory, String ecosystem, String name, String installed) {
        if (ecosystem == null || name == null || installed == null || installed.isBlank()) return unavailable("MISSING_IDENTITY");
        if (advisory == null || !advisory.isObject() || !advisory.path("affected").isArray()) return unavailable("MALFORMED_ADVISORY");
        OsvWithdrawal withdrawal = OsvWithdrawal.from(advisory);
        if (withdrawal == OsvWithdrawal.WITHDRAWN) return unavailable("WITHDRAWN");
        if (withdrawal == OsvWithdrawal.UNKNOWN) return unavailable("MALFORMED_WITHDRAWAL");
        List<JsonNode> entries = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        Comparator<String> ordering = null;
        String rangeType = null;
        try {
            for (JsonNode entry : advisory.path("affected")) {
                JsonNode pkg = entry.path("package");
                if (!ecosystem.equals(pkg.path("ecosystem").asText())) continue;
                if (!AdvisoryPackageNames.canonical(ecosystem, name).equals(
                        AdvisoryPackageNames.canonical(ecosystem, pkg.path("name").asText()))) continue;
                if (entry.has("versions") && !entry.path("versions").isArray()) return unavailable("MALFORMED_VERSIONS");
                for (JsonNode version : entry.path("versions")) {
                    if (!version.isTextual() || version.asText().isBlank()) return unavailable("MALFORMED_VERSIONS");
                }
                entries.add(entry);
                if (!entry.path("ranges").isArray() || entry.path("ranges").isEmpty()) return unavailable("NO_RANGE_EVIDENCE");
                for (JsonNode range : entry.path("ranges")) {
                    String type = range.path("type").asText();
                    Comparator<String> comparator = OsvRangeEvaluator.comparator(ecosystem.toUpperCase(java.util.Locale.ROOT), type);
                    if (comparator == null) return unavailable("UNSUPPORTED_RANGE");
                    if (rangeType != null && !type.equals(rangeType) && !"npm".equals(ecosystem)) {
                        return unavailable("INCOMPATIBLE_RANGE_TYPES");
                    }
                    rangeType = type;
                    ordering = comparator;
                    var singleRange = JsonNodeFactory.instance.arrayNode().add(range);
                    var affected = OsvRangeEvaluator.evaluate(ecosystem.toUpperCase(java.util.Locale.ROOT), installed, null, singleRange);
                    if (affected == OsvRangeEvaluator.Result.UNKNOWN) return unavailable("UNRESOLVED_RANGE");
                    if (affected != OsvRangeEvaluator.Result.AFFECTED) continue;
                    for (JsonNode event : range.path("events")) {
                        if (event.path("fixed").isTextual()) {
                            String fixed = event.path("fixed").asText();
                            if (comparator.compare(fixed, installed) > 0) candidates.add(fixed);
                        }
                    }
                }
            }
            if (entries.isEmpty()) return unavailable("NO_MATCHING_PACKAGE");
            if (candidates.isEmpty()) return unavailable("NO_APPLICABLE_FIXED_EVENT");
            List<String> confirmed = new ArrayList<>();
            for (String candidate : candidates) {
                boolean excludedFromAll = true;
                for (JsonNode entry : entries) {
                    Set<String> versions = new LinkedHashSet<>();
                    for (JsonNode version : entry.path("versions")) {
                        versions.add(version.asText());
                    }
                    excludedFromAll &= OsvRangeEvaluator.evaluate(ecosystem.toUpperCase(java.util.Locale.ROOT), candidate,
                            versions, entry.path("ranges")) == OsvRangeEvaluator.Result.NOT_AFFECTED;
                }
                if (excludedFromAll) confirmed.add(candidate);
            }
            if (confirmed.isEmpty()) return unavailable("FIX_CONFLICTS_WITH_AFFECTED_DATA");
            confirmed.sort(ordering);
            return new Selection(confirmed.getFirst(), "SOURCE_FIXED_EVENT");
        } catch (IllegalArgumentException unsupported) {
            return unavailable("UNSUPPORTED_VERSION");
        }
    }

    private static Selection unavailable(String reason) { return new Selection(null, reason); }
}
