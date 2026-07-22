package com.salkcoding.oswl.util;

import com.salkcoding.oswl.domain.entity.ScanResult;

import java.util.Comparator;
import java.util.List;

/**
 * Semantic-version-aware ordering for scan results.
 *
 * Scans are imported in arbitrary order, so scanned_at does not reflect the
 * actual version sequence (e.g. importing 1.0.2 before 1.0.1 made 1.0.2 appear
 * "older"). Version strings are compared segment-by-segment: numeric segments
 * numerically, everything else lexicographically. Scans without a version fall
 * back to scannedAt so they still order deterministically.
 */
public final class VersionOrder {

    private VersionOrder() {
    }

    /** Newest version first; null-version scans sort after versioned ones by scannedAt desc. */
    public static final Comparator<ScanResult> SCAN_DESC = scanAsc().reversed();

    /** Sorts the given list in place, newest version first. */
    public static void sortDesc(List<ScanResult> scans) {
        scans.sort(SCAN_DESC);
    }

    private static Comparator<ScanResult> scanAsc() {
        return (a, b) -> {
            String va = a.getVersion();
            String vb = b.getVersion();
            if (va != null && vb != null) {
                int c = compareVersions(va, vb);
                if (c != 0) return c;
            } else if (va != null) {
                return 1;   // versioned sorts above (after reversal) unversioned
            } else if (vb != null) {
                return -1;
            }
            if (a.getScannedAt() == null || b.getScannedAt() == null) return 0;
            return a.getScannedAt().compareTo(b.getScannedAt());
        };
    }

    /** Compares dotted version strings: numeric segments numerically, others lexicographically. */
    private static int compareVersions(String a, String b) {
        String[] as = normalize(a).split("[.\\-_+]");
        String[] bs = normalize(b).split("[.\\-_+]");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            // A missing segment ranks below any present one (1.0 < 1.0.1),
            // except a present pre-release tag ranks below the bare version (1.0 > 1.0-rc1).
            String sa = i < as.length ? as[i] : null;
            String sb = i < bs.length ? bs[i] : null;
            if (sa == null) return isNumeric(sb) ? -1 : 1;
            if (sb == null) return isNumeric(sa) ? 1 : -1;
            int c;
            if (isNumeric(sa) && isNumeric(sb)) {
                c = Long.compare(Long.parseLong(sa), Long.parseLong(sb));
            } else if (isNumeric(sa)) {
                c = 1;  // numeric segment ranks above pre-release tag (1.0.1 > 1.0-rc1)
            } else if (isNumeric(sb)) {
                c = -1;
            } else {
                c = sa.compareToIgnoreCase(sb);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static String normalize(String v) {
        String s = v.strip();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        return s;
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
