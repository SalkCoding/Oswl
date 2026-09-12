package com.salkcoding.oswl.vdb;

import java.util.Locale;

/** Ecosystem-specific lookup identity, separate from persisted/displayed package names. */
public final class AdvisoryPackageNames {
    private AdvisoryPackageNames() { }

    /** OSV's whole-name wildcard applies within an already matched ecosystem only. */
    public static boolean matchesOsvName(String ecosystem, String queryName, String advisoryName) {
        if (queryName == null || queryName.isBlank()) throw new IllegalArgumentException("Missing package name");
        String canonicalQuery = canonical(ecosystem, queryName);
        return "*".equals(advisoryName) || canonicalQuery.equals(canonical(ecosystem, advisoryName));
    }

    public static String canonical(String ecosystem, String name) {
        if ("NUGET".equalsIgnoreCase(ecosystem)) {
            if (name == null || name.length() > 4096
                    || !name.matches("[\\p{L}\\p{Mn}\\p{Nd}\\p{Pc}]++(?:[.-][\\p{L}\\p{Mn}\\p{Nd}\\p{Pc}]++)*"))
                throw new IllegalArgumentException("Invalid NuGet package name");
            StringBuilder folded = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char value = name.charAt(i);
                // These case pairs differ between Java's Unicode table and the verified .NET runtime.
                // Keep their identity unresolved until the provider's casing contract is known.
                if (Character.isSurrogate(value) || switch (value) {
                    case '\u019b', '\u0264', '\u1c89', '\u1c8a', '\ua7cb', '\ua7cc', '\ua7cd',
                            '\ua7da', '\ua7db', '\ua7dc' -> true;
                    default -> false;
                }) throw new IllegalArgumentException("Unsupported NuGet package name casing");
                char upper = Character.toUpperCase(value);
                // Ordinal identity neither expands characters nor merges non-ASCII letters into ASCII.
                folded.append(value < 128 ? Character.toLowerCase(value) : upper < 128 ? value : upper);
            }
            return folded.toString();
        }
        if (!"PYPI".equalsIgnoreCase(ecosystem) && !"PIP".equalsIgnoreCase(ecosystem)) return name;
        if (name == null || name.length() > 4096 || !name.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Invalid PyPI package name");
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
