package com.salkcoding.oswl.vdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Alpine {@code apk} package version comparator — Alpine's OSV advisories carry
 * only {@code ECOSYSTEM}-typed ranges (no enumerated {@code versions[]}), and apk's version
 * scheme isn't SemVer, so {@link SimpleVersionComparator} can't be reused for it (Debian/Ubuntu
 * needed no comparator at all — OSV enumerates their {@code versions[]} directly).
 *
 * <p>Grammar handled, in the order apk itself defines it: {@code N(.N)*} numeric segments, an
 * optional single trailing letter, an optional {@code _suffix[num]} (one of alpha/beta/pre/rc/
 * cvs/svn/git/hg/p), and an optional {@code -rN} revision. Comparison order:
 * <ol>
 *   <li>numeric segments, compared as integers (not strings) left to right</li>
 *   <li>the trailing letter, if any — absent sorts below present ({@code 1.0 < 1.0a})</li>
 *   <li>the {@code _suffix}'s rank: alpha &lt; beta &lt; pre &lt; rc &lt; (no suffix) &lt; cvs
 *       &lt; svn &lt; git &lt; hg &lt; p, then its optional trailing number</li>
 *   <li>the {@code -rN} revision (defaults to 0 when omitted, so {@code 1.1.1l} and
 *       {@code 1.1.1l-r0} compare equal)</li>
 * </ol>
 *
 * <p>Any version this grammar doesn't match throws {@link IllegalArgumentException} — callers
 * must treat that as "unresolved coverage", never as "not affected", the same contract
 * {@link SimpleVersionComparator} already uses.
 */
final class ApkVersionComparator {

    // The underscore before the suffix word is optional: real OSV advisory data for Alpine
    // renders e.g. "3.3.3p1-r3" without it (letter directly followed by the suffix's own
    // number), not apk-tools' own canonical "_p1" form — both must parse to the same suffix.
    private static final Pattern APK_VERSION = Pattern.compile(
            "^(?<nums>\\d+(?:\\.\\d+)*)"
                    + "(?:_?(?<suffix>alpha|beta|pre|rc|cvs|svn|git|hg|p)(?<suffixnum>\\d*)"
                    + "|(?<letter>[a-z]))?"
                    + "(?:-r(?<rev>\\d+))?$");

    /** Suffix rank — "no suffix" sits between rc and cvs, per apk's own ordering. */
    private static final Map<String, Integer> SUFFIX_RANK = Map.of(
            "alpha", 0, "beta", 1, "pre", 2, "rc", 3, "cvs", 5, "svn", 6, "git", 7, "hg", 8, "p", 9);
    private static final int NO_SUFFIX_RANK = 4;

    private ApkVersionComparator() {}

    /** {@code true} if {@code introduced <= version < fixed} (fixed may be null = unbounded above). */
    static boolean inRange(String version, String introduced, String fixed) {
        if (introduced != null && compare(version, introduced) < 0) return false;
        if (fixed != null && compare(version, fixed) >= 0) return false;
        return true;
    }

    static int compare(String a, String b) {
        Parsed pa = parse(a);
        Parsed pb = parse(b);

        int numCmp = compareNums(pa.nums, pb.nums);
        if (numCmp != 0) return numCmp;

        int letterCmp = Integer.compare(
                pa.letter == null ? -1 : pa.letter,
                pb.letter == null ? -1 : pb.letter);
        if (letterCmp != 0) return letterCmp;

        int suffixRankCmp = Integer.compare(pa.suffixRank, pb.suffixRank);
        if (suffixRankCmp != 0) return suffixRankCmp;

        int suffixNumCmp = Integer.compare(pa.suffixNum, pb.suffixNum);
        if (suffixNumCmp != 0) return suffixNumCmp;

        return Integer.compare(pa.revision, pb.revision);
    }

    private static int compareNums(List<Integer> a, List<Integer> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            int va = i < a.size() ? a.get(i) : 0;
            int vb = i < b.size() ? b.get(i) : 0;
            int cmp = Integer.compare(va, vb);
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    private record Parsed(List<Integer> nums, Character letter, int suffixRank, int suffixNum, int revision) {}

    private static Parsed parse(String version) {
        String v = version.strip();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        Matcher m = APK_VERSION.matcher(v);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unparseable apk version: " + version);
        }

        List<Integer> nums = new ArrayList<>();
        for (String seg : m.group("nums").split("\\.")) {
            nums.add(Integer.parseInt(seg));
        }

        String letterGroup = m.group("letter");
        Character letter = letterGroup == null ? null : letterGroup.charAt(0);

        String suffix = m.group("suffix");
        int suffixRank = suffix == null ? NO_SUFFIX_RANK : SUFFIX_RANK.get(suffix);
        String suffixNumGroup = m.group("suffixnum");
        int suffixNum = (suffixNumGroup == null || suffixNumGroup.isEmpty()) ? 0 : Integer.parseInt(suffixNumGroup);

        String revGroup = m.group("rev");
        int revision = revGroup == null ? 0 : Integer.parseInt(revGroup);

        return new Parsed(nums, letter, suffixRank, suffixNum, revision);
    }
}
