package com.salkcoding.oswl.license;

import com.salkcoding.oswl.domain.enums.LicenseStatus;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic default classification for SPDX license identifiers.
 * Organization admins can override any entry in Settings.
 */
public final class SpdxLicenseClassifier {

    private static final Pattern STRONG_COPYLEFT = Pattern.compile(
            "^(AGPL|SSPL|BUSL|RPL|NPOSL|CPAL|OSL|CC-BY-NC|CC-BY-SA|Commons-Clause).*",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> RESTRICTED_EXACT = Set.of(
            "JSON", "GNU-FDL-1.3", "GNU-FDL-1.2", "GNU-FDL-1.1");

    private static final Pattern WEAK_COPYLEFT = Pattern.compile(
            "^(LGPL|MPL|CDDL|EPL|EUPL|CPL|IPL|APSL|Artistic|MS-RL|NPL|QPL|SPL|RSCPL|Sleepycat|CNRI-Python|Motosoto|libtelnet|Watcom|SISSL|LPPL).*",
            Pattern.CASE_INSENSITIVE);

    private SpdxLicenseClassifier() {}

    public static LicenseStatus classify(String spdxId, boolean osiApproved) {
        if (spdxId == null || spdxId.isBlank()) {
            return LicenseStatus.UNKNOWN;
        }
        String id = spdxId.strip();

        if (RESTRICTED_EXACT.contains(id)) {
            return LicenseStatus.RESTRICTED;
        }
        if (isGplFamily(id)) {
            return LicenseStatus.RESTRICTED;
        }
        if (STRONG_COPYLEFT.matcher(id).find()) {
            return LicenseStatus.RESTRICTED;
        }
        if (WEAK_COPYLEFT.matcher(id).find()) {
            return LicenseStatus.CAUTION;
        }
        if (osiApproved) {
            return LicenseStatus.PERMITTED;
        }
        // Obscure / non-OSI SPDX IDs still get a default row, but need review.
        return LicenseStatus.CAUTION;
    }

    public static String defaultReason(LicenseStatus status) {
        return switch (status) {
            case PERMITTED -> "Permissive open-source license";
            case CAUTION -> "Copyleft or notice obligations — review recommended";
            case RESTRICTED -> "Strong copyleft or commercial restriction";
            case UNKNOWN -> "Unknown license — requires manual classification";
        };
    }

    private static boolean isGplFamily(String id) {
        String upper = id.toUpperCase(Locale.ROOT);
        if (upper.contains("LGPL")) {
            return false;
        }
        return upper.startsWith("GPL-") || upper.equals("GPL");
    }
}
