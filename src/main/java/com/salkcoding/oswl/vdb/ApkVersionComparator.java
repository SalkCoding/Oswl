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
 * optional single trailing letter, zero or more {@code _suffix[num]} parts (one of alpha/beta/pre/rc/
 * cvs/svn/git/hg/p), an optional hexadecimal {@code ~hash}, and an optional {@code -rN} revision. Comparison order:
 * <ol>
 *   <li>numeric segments, with string ordering for leading-zero segments after the first</li>
 *   <li>the trailing letter, if any — absent sorts below present ({@code 1.0 < 1.0a})</li>
 *   <li>the {@code _suffix}'s rank: alpha &lt; beta &lt; pre &lt; rc &lt; (no suffix) &lt; cvs
 *       &lt; svn &lt; git &lt; hg &lt; p, then its optional trailing number</li>
 *   <li>the {@code -rN} revision; absence sorts before an explicit zero revision</li>
 * </ol>
 *
 * <p>Any version this grammar doesn't match throws {@link IllegalArgumentException} — callers
 * must treat that as "unresolved coverage", never as "not affected", the same contract
 * {@link SimpleVersionComparator} already uses.
 */
final class ApkVersionComparator {

    private static final Pattern APK_VERSION = Pattern.compile(
            "^(?<nums>\\d+(?:\\.\\d+)*)"
                    + "(?<letter>[a-z])?"
                    + "(?<suffixes>(?:_(?:alpha|beta|pre|rc|cvs|svn|git|hg|p)\\d*+)*+)"
                    + "(?:~(?<hash>[0-9a-fA-F]+))?"
                    + "(?:-r(?<rev>\\d+))?$");

    private static final Pattern SUFFIX = Pattern.compile("_(alpha|beta|pre|rc|cvs|svn|git|hg|p)(\\d*)");

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

        return compareTail(pa.tail, pb.tail);
    }

    private enum TailKind { SUFFIX, NUMBER, HASH, REVISION, END }
    private record TailPart(TailKind kind, long value, String text) {
        TailPart(TailKind kind, long value) { this(kind, value, null); }
    }
    private static final TailPart END = new TailPart(TailKind.END, 0);

    private static int compareTail(List<TailPart> left, List<TailPart> right) {
        for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
            TailPart a = i < left.size() ? left.get(i) : END;
            TailPart b = i < right.size() ? right.get(i) : END;
            if (a.kind == b.kind) {
                int compared = a.kind == TailKind.HASH ? a.text.compareTo(b.text) : Long.compareUnsigned(a.value, b.value);
                if (compared != 0) return compared;
            } else {
                if (a.kind == TailKind.SUFFIX && a.value < NO_SUFFIX_RANK) return -1;
                if (b.kind == TailKind.SUFFIX && b.value < NO_SUFFIX_RANK) return 1;
                return Integer.compare(b.kind.ordinal(), a.kind.ordinal());
            }
        }
        return 0;
    }

    private static int compareNums(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            String va = a.get(i);
            String vb = b.get(i);
            int cmp = i > 0 && (va.startsWith("0") || vb.startsWith("0"))
                    ? va.compareTo(vb) : Long.compareUnsigned(Long.parseUnsignedLong(va), Long.parseUnsignedLong(vb));
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.size(), b.size());
    }

    private record Parsed(List<String> nums, Character letter, List<TailPart> tail) {}

    private static Parsed parse(String version) {
        if (version == null || version.length() > 4096)
            throw new IllegalArgumentException("Missing or unsupported apk version");
        Matcher m = APK_VERSION.matcher(version);
        if (!m.matches()) {
            throw new IllegalArgumentException("Unparseable apk version: " + version);
        }

        List<String> nums = new ArrayList<>();
        for (String seg : m.group("nums").split("\\.")) {
            Long.parseUnsignedLong(seg);
            nums.add(seg);
        }

        String letterGroup = m.group("letter");
        Character letter = letterGroup == null ? null : letterGroup.charAt(0);

        List<TailPart> tail = new ArrayList<>();
        Matcher suffixes = SUFFIX.matcher(m.group("suffixes"));
        while (suffixes.find()) {
            tail.add(new TailPart(TailKind.SUFFIX, SUFFIX_RANK.get(suffixes.group(1))));
            if (!suffixes.group(2).isEmpty())
                tail.add(new TailPart(TailKind.NUMBER, Long.parseUnsignedLong(suffixes.group(2))));
        }
        String hash = m.group("hash");
        if (hash != null) tail.add(new TailPart(TailKind.HASH, 0, hash));
        String revision = m.group("rev");
        if (revision != null) tail.add(new TailPart(TailKind.REVISION, Long.parseUnsignedLong(revision)));
        return new Parsed(nums, letter, List.copyOf(tail));
    }
}
