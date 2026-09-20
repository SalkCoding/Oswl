package com.salkcoding.oswl.vdb;

/** Shared OSV lookup and bulk-collection identity validation for supported comparators. */
public final class OsvQueryIdentity {
    private OsvQueryIdentity() { }

    public static boolean isConcrete(String ecosystem, String name, String version) {
        try {
            if (com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey(ecosystem, name, version) == null)
                return false;
            ecosystem = ecosystem.strip().toUpperCase(java.util.Locale.ROOT);
            AdvisoryPackageNames.canonical(ecosystem, name);
            if (ecosystem.startsWith("ALPINE:")) ApkVersionComparator.compare(version, version);
            switch (ecosystem) {
                case "NPM", "CARGO", "CRATES.IO" -> SemVerVersionComparator.compare(version, version);
                case "GO" -> GoVersionComparator.compare(version, version);
                case "PYPI", "PIP" -> Pep440VersionComparator.compare(version, version);
                case "MAVEN" -> MavenVersionComparator.compare(version, version);
                case "NUGET" -> NuGetVersionComparator.compare(version, version);
                default -> { }
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

}
