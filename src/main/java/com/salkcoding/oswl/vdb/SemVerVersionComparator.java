package com.salkcoding.oswl.vdb;

import java.util.regex.Pattern;

/** Strict SemVer 2.0 precedence for explicitly SEMVER-typed data, not arbitrary ecosystems. */
public final class SemVerVersionComparator {
    private static final Pattern VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "(?:-([0-9A-Za-z-]++(?:\\.[0-9A-Za-z-]++)*+))?"
                    + "(?:\\+([0-9A-Za-z-]++(?:\\.[0-9A-Za-z-]++)*+))?");

    private SemVerVersionComparator() { }

    public static int compare(String left, String right) {
        Version a = parse(left);
        Version b = parse(right);
        for (int i = 0; i < 3; i++) {
            int comparison = compareNumeric(a.core()[i], b.core()[i]);
            if (comparison != 0) return comparison;
        }
        if (a.prerelease().length == 0 || b.prerelease().length == 0) {
            return Integer.compare(a.prerelease().length == 0 ? 1 : 0, b.prerelease().length == 0 ? 1 : 0);
        }
        for (int i = 0; i < Math.min(a.prerelease().length, b.prerelease().length); i++) {
            String x = a.prerelease()[i];
            String y = b.prerelease()[i];
            boolean nx = numeric(x);
            boolean ny = numeric(y);
            int comparison = nx && ny ? compareNumeric(x, y)
                    : nx != ny ? (nx ? -1 : 1) : x.compareTo(y);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(a.prerelease().length, b.prerelease().length);
    }

    private static Version parse(String value) {
        if (value == null || value.length() > 4096) throw new IllegalArgumentException("Unsupported SemVer value");
        var matcher = VERSION.matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid SemVer value");
        String[] prerelease = matcher.group(4) == null ? new String[0] : matcher.group(4).split("\\.");
        for (String identifier : prerelease) {
            if (numeric(identifier) && identifier.length() > 1 && identifier.charAt(0) == '0') {
                throw new IllegalArgumentException("Leading zero in SemVer prerelease");
            }
        }
        return new Version(new String[]{matcher.group(1), matcher.group(2), matcher.group(3)}, prerelease);
    }

    private static boolean numeric(String value) {
        return value.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static int compareNumeric(String a, String b) {
        int length = Integer.compare(a.length(), b.length());
        return length == 0 ? a.compareTo(b) : length;
    }

    private record Version(String[] core, String[] prerelease) { }
}
