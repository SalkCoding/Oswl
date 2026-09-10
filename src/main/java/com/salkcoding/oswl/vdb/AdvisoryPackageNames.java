package com.salkcoding.oswl.vdb;

import java.util.Locale;

/** Ecosystem-specific lookup identity, separate from persisted/displayed package names. */
public final class AdvisoryPackageNames {
    private AdvisoryPackageNames() { }

    public static String canonical(String ecosystem, String name) {
        if (!"PYPI".equalsIgnoreCase(ecosystem) && !"PIP".equalsIgnoreCase(ecosystem)) return name;
        if (name == null || name.length() > 4096 || !name.matches("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?"))
            throw new IllegalArgumentException("Invalid PyPI package name");
        return name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }
}
