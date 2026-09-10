package com.salkcoding.oswl.vdb;

/** SemVer precedence for canonical Go module versions and advisory versions without the v prefix. */
public final class GoVersionComparator {
    private GoVersionComparator() { }

    public static int compare(String left, String right) {
        return SemVerVersionComparator.compare(withoutPrefix(left), withoutPrefix(right));
    }

    static boolean sameVersion(String left, String right) {
        String a = withoutPrefix(left);
        String b = withoutPrefix(right);
        SemVerVersionComparator.compare(a, b);
        // Prefix spelling does not change identity; build metadata may describe different code.
        return a.equals(b);
    }

    private static String withoutPrefix(String version) {
        if (version == null || version.length() > 4096) throw new IllegalArgumentException("Invalid Go module version");
        return version.startsWith("v") ? version.substring(1) : version;
    }
}
