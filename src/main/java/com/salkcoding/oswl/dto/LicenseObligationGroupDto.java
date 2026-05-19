package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class LicenseObligationGroupDto {

    /** Stable rule key, e.g. "DISCLOSE_SOURCE". Useful for UI anchors / data attributes. */
    String key;

    /** Human-readable obligation name, e.g. "Include License Notice". */
    String label;

    /** One-sentence description of what this obligation requires. */
    String description;

    /** Risk level: "LOW" (permissive), "MEDIUM" (weak-copyleft), "HIGH" (strong/network copyleft). */
    String riskLevel;

    /** Short citation pointing to the relevant clause(s) of the underlying license(s). */
    String clauseCitation;

    /**
     * Markdown / plain-text snippet a compliance team can paste into a NOTICE / THIRD_PARTY_LICENSES
     * file to satisfy this obligation. Includes a {LIBRARY_LIST} placeholder.
     */
    String noticeTemplate;

    /** Per-license breakdown — one entry per distinct SPDX identifier found in the scan. */
    List<LicenseEntry> entries;

    @Value
    @Builder
    public static class LicenseEntry {
        /** The license name as stored on the Library entity (SPDX expression string). */
        String spdxId;
        /** Canonical SPDX URL (https://spdx.org/licenses/<id>.html) — null when not a known SPDX id. */
        String spdxUrl;
        /**
         * True when this license was matched as one option inside an SPDX OR expression
         * (e.g. "MIT OR Apache-2.0"). Compliance teams may choose the cheaper option.
         */
        boolean orChoice;
        /** Sorted list of "artifactId version" strings for libraries carrying this license. */
        List<String> libraries;
    }
}
