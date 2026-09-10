package com.salkcoding.oswl.vdb;

import java.util.regex.Pattern;

/** Concrete NuGet version precedence, excluding build metadata and dependency range syntax. */
public final class NuGetVersionComparator {
    private static final Pattern VERSION = Pattern.compile(
            "([0-9]++(?:\\.[0-9]++){0,3})(?:-([A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)*+))?"
                    + "(?:\\+([A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)*+))?");

    private NuGetVersionComparator() { }

    public static int compare(String left, String right) {
        Version a = parse(left), b = parse(right);
        for (int i = 0; i < 4; i++) {
            int c = Integer.compare(a.release[i], b.release[i]);
            if (c != 0) return c;
        }
        if (a.labels == null || b.labels == null) return a.labels == b.labels ? 0 : a.labels == null ? 1 : -1;
        for (int i = 0; i < Math.min(a.labels.length, b.labels.length); i++) {
            String x = a.labels[i], y = b.labels[i];
            Integer xn = labelNumber(x), yn = labelNumber(y);
            int c = xn != null && yn != null ? xn.compareTo(yn)
                    : xn != null ? -1 : yn != null ? 1 : x.compareToIgnoreCase(y);
            if (c != 0) return c;
        }
        return Integer.compare(a.labels.length, b.labels.length);
    }

    private static Integer labelNumber(String label) {
        // NuGet VersionRelease treats labels outside Int32 as text, unlike strict SemVer.
        try { return Integer.valueOf(label); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static Version parse(String raw) {
        if (raw == null || raw.length() > 4096) throw new IllegalArgumentException("Invalid NuGet version");
        var match = VERSION.matcher(raw.strip());
        if (!match.matches()) throw new IllegalArgumentException("Invalid NuGet version");
        int[] release = new int[4];
        String[] parts = match.group(1).split("\\.");
        for (int i = 0; i < parts.length; i++) release[i] = Integer.parseInt(parts[i]);
        String[] labels = match.group(2) == null ? null : match.group(2).split("\\.");
        if (labels != null) {
            for (String label : labels) {
                if (label.length() > 1 && label.charAt(0) == '0' && label.chars().allMatch(c -> c >= '0' && c <= '9'))
                    throw new IllegalArgumentException("Invalid NuGet numeric prerelease label");
            }
        }
        return new Version(release, labels);
    }

    private record Version(int[] release, String[] labels) { }
}
