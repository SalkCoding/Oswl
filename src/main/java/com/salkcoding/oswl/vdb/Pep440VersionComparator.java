package com.salkcoding.oswl.vdb;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** PEP 440 concrete version ordering; dependency specifiers and environment markers are separate. */
public final class Pep440VersionComparator {
    private static final Pattern VERSION = Pattern.compile(
            "v?(?:(?<epoch>[0-9]++)!)?(?<release>[0-9]++(?:\\.[0-9]++)*+)"
            + "(?:[-_.]?(?<pre>alpha|beta|preview|pre|rc|a|b|c)[-_.]?(?<preNumber>[0-9]*+))?"
            + "(?:-(?<dashPost>[0-9]++)|[-_.]?(?<post>post|rev|r)[-_.]?(?<postNumber>[0-9]*+))?"
            + "(?:[-_.]?(?<dev>dev)[-_.]?(?<devNumber>[0-9]*+))?"
            + "(?:\\+(?<local>[a-z0-9]++(?:[-_.][a-z0-9]++)*+))?");

    private Pep440VersionComparator() { }

    public static int compare(String left, String right) {
        Version a = parse(left);
        Version b = parse(right);
        int c = a.epoch.compareTo(b.epoch);
        if (c != 0) return c;
        for (int i = 0; i < Math.max(a.release.size(), b.release.size()); i++) {
            c = (i < a.release.size() ? a.release.get(i) : BigInteger.ZERO)
                    .compareTo(i < b.release.size() ? b.release.get(i) : BigInteger.ZERO);
            if (c != 0) return c;
        }
        c = Integer.compare(a.prePhase, b.prePhase);
        if (c != 0) return c;
        c = a.preNumber.compareTo(b.preNumber);
        if (c != 0) return c;
        c = optionalNumber(a.post, b.post, -1);
        if (c != 0) return c;
        c = optionalNumber(a.dev, b.dev, 1);
        if (c != 0) return c;
        if (a.local == null || b.local == null) return a.local == b.local ? 0 : a.local == null ? -1 : 1;
        for (int i = 0; i < Math.min(a.local.length, b.local.length); i++) {
            String x = a.local[i], y = b.local[i];
            boolean xn = x.chars().allMatch(ch -> ch >= '0' && ch <= '9');
            boolean yn = y.chars().allMatch(ch -> ch >= '0' && ch <= '9');
            c = xn && yn ? number(x).compareTo(number(y)) : xn != yn ? Boolean.compare(xn, yn) : x.compareTo(y);
            if (c != 0) return c;
        }
        return Integer.compare(a.local.length, b.local.length);
    }

    private static int optionalNumber(BigInteger a, BigInteger b, int absentOrder) {
        if (a == null || b == null) return a == b ? 0 : a == null ? absentOrder : -absentOrder;
        return a.compareTo(b);
    }

    private static BigInteger number(String raw) {
        return raw == null || raw.isEmpty() ? BigInteger.ZERO : new BigInteger(raw);
    }

    private static Version parse(String raw) {
        if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Invalid PEP 440 version");
        var match = VERSION.matcher(raw.strip().toLowerCase(Locale.ROOT));
        if (!match.matches()) throw new IllegalArgumentException("Invalid PEP 440 version");
        BigInteger post = match.group("dashPost") != null ? number(match.group("dashPost"))
                : match.group("post") != null ? number(match.group("postNumber")) : null;
        BigInteger dev = match.group("dev") != null ? number(match.group("devNumber")) : null;
        String pre = match.group("pre");
        int phase = pre == null ? (dev != null && post == null ? -1 : 3) : switch (pre) {
            case "a", "alpha" -> 0;
            case "b", "beta" -> 1;
            default -> 2;
        };
        String local = match.group("local");
        return new Version(number(match.group("epoch")),
                Arrays.stream(match.group("release").split("\\.")).map(Pep440VersionComparator::number).toList(),
                phase, number(match.group("preNumber")), post, dev, local == null ? null : local.split("[-_.]"));
    }

    private record Version(BigInteger epoch, List<BigInteger> release, int prePhase, BigInteger preNumber,
                           BigInteger post, BigInteger dev, String[] local) { }
}
