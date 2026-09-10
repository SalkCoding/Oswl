package com.salkcoding.oswl.vdb;

import org.apache.maven.artifact.versioning.ComparableVersion;

/** Maven ordering for concrete artifact versions, separate from dependency range syntax. */
public final class MavenVersionComparator {
    private MavenVersionComparator() { }

    public static int compare(String left, String right) {
        validate(left);
        validate(right);
        return new ComparableVersion(left).compareTo(new ComparableVersion(right));
    }

    static void validate(String version) {
        if (version == null || version.isBlank() || version.length() > 4096
                || "LATEST".equals(version) || "RELEASE".equals(version)
                || version.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)
                || "[](),<>=$".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("Expected a concrete Maven artifact version");
        }
    }
}
