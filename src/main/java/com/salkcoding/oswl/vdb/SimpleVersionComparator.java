package com.salkcoding.oswl.vdb;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort version comparator for OSV {@code SEMVER}-typed ranges, used only as a fallback when
 * an {@code affected[]} entry has no enumerated {@code versions[]} list (see E5.3 in
 * PERFORMANCE-AND-OFFLINE-PLAN.md — the plan explicitly calls "proper per-ecosystem version
 * comparison" a separate, substantial piece of work).
 *
 * <p>This is a generic dotted-segment comparator (numeric segments compare numerically, other
 * segments lexicographically; a leading {@code v} is stripped), <b>not</b> a real SemVer/PEP440/
 * Maven comparator. It handles ordinary release version ordering correctly but does not implement
 * SemVer's prerelease-precedence rules (e.g. it may not always rank {@code 1.0.0-alpha} below
 * {@code 1.0.0}). Any comparison this class cannot make sense of throws
 * {@link IllegalArgumentException}, which callers must treat as "unresolved coverage", never as
 * "not affected".
 */
public final class SimpleVersionComparator {

    private static final Pattern SEGMENT = Pattern.compile("[0-9]+|[^0-9.+-]+|[.+-]");

    private SimpleVersionComparator() {}

    /** {@code true} if {@code introduced <= version < fixed} (fixed may be null = unbounded above). */
    static boolean inRange(String version, String introduced, String fixed) {
        if (introduced != null && compare(version, introduced) < 0) return false;
        if (fixed != null && compare(version, fixed) >= 0) return false;
        return true;
    }

    public static int compare(String a, String b) {
        List<String> sa = tokenize(a);
        List<String> sb = tokenize(b);
        int n = Math.max(sa.size(), sb.size());
        for (int i = 0; i < n; i++) {
            String ta = i < sa.size() ? sa.get(i) : "0";
            String tb = i < sb.size() ? sb.get(i) : "0";
            boolean numA = isNumeric(ta);
            boolean numB = isNumeric(tb);
            int cmp;
            if (numA && numB) {
                cmp = Long.compare(Long.parseLong(ta), Long.parseLong(tb));
            } else if (numA != numB) {
                // a numeric segment lines up against a non-numeric one (e.g. "1" vs "rc1") —
                // ambiguous under any real scheme; refuse rather than guess.
                throw new IllegalArgumentException("Cannot compare heterogeneous version segments: " + a + " vs " + b);
            } else {
                cmp = ta.compareTo(tb);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit);
    }

    private static List<String> tokenize(String version) {
        String v = version.strip();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        List<String> tokens = new ArrayList<>();
        Matcher m = SEGMENT.matcher(v);
        while (m.find()) {
            String tok = m.group();
            if (".".equals(tok) || "+".equals(tok) || "-".equals(tok)) continue; // pure separators
            tokens.add(tok);
        }
        if (tokens.isEmpty()) throw new IllegalArgumentException("Unparseable version: " + version);
        return tokens;
    }
}
