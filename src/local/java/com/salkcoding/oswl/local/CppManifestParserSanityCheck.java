package com.salkcoding.oswl.local;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.CondaPypiMappingService;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService;
import com.salkcoding.oswl.service.ingest.MavenBomVersionResolver;

import java.nio.file.Path;
import java.util.List;

/**
 * Throwaway sanity check for the C/C++ manifest parsers (vcpkg.json, .gitmodules, conan.lock).
 * Not part of the build or test suite — compiled and run manually against fixtures under
 * args[0]/{vcpkg,conan,submodule}, mirroring {@link LockParserSanityCheck}.
 */
public final class CppManifestParserSanityCheck {

    private CppManifestParserSanityCheck() {}

    static void main(String[] args) throws Exception {
        DependencyManifestParserService parser =
                new DependencyManifestParserService(new MavenBomVersionResolver(), new CondaPypiMappingService());
        int failures = 0;

        Path vcpkgDir = Path.of(args[0], "vcpkg");
        Path conanDir = Path.of(args[0], "conan");
        Path submoduleDir = Path.of(args[0], "submodule");

        var vcpkgResult = parser.parseDependencies(vcpkgDir, "vcpkg-fixture");
        failures += check("vcpkg ecosystem", "VCPKG".equals(vcpkgResult.ecosystem()));
        ScanPayload.ComponentPayload openssl = find(vcpkgResult.components(), "openssl");
        failures += check("vcpkg openssl>=1.1.1k parsed",
                openssl != null && "1.1.1k".equals(openssl.getVersion()));
        failures += check("vcpkg zlib (unversioned string entry) parsed",
                find(vcpkgResult.components(), "zlib") != null);
        System.out.printf("  [vcpkg] %s%n", describe(vcpkgResult.components()));

        var conanResult = parser.parseDependencies(conanDir, "conan-fixture");
        failures += check("conan ecosystem", "CONAN".equals(conanResult.ecosystem()));
        ScanPayload.ComponentPayload conanOpenssl = find(conanResult.components(), "openssl");
        failures += check("conan openssl/1.1.1k parsed",
                conanOpenssl != null && "1.1.1k".equals(conanOpenssl.getVersion()));
        System.out.printf("  [conan] %s%n", describe(conanResult.components()));

        var submoduleResult = parser.parseDependencies(submoduleDir, "submodule-fixture");
        failures += check("submodule ecosystem", "SUBMODULE".equals(submoduleResult.ecosystem()));
        ScanPayload.ComponentPayload sub = find(submoduleResult.components(), "third_party/openssl");
        failures += check("submodule pinned SHA resolved (not a semantic version)",
                sub != null && "fd78df59b0f656aefe96e39533130454aa957c00".equals(sub.getVersion()));
        System.out.printf("  [submodule] %s%n", describe(submoduleResult.components()));

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static ScanPayload.ComponentPayload find(List<ScanPayload.ComponentPayload> comps, String name) {
        return comps.stream().filter(c -> name.equals(c.getName())).findFirst().orElse(null);
    }

    private static String describe(List<ScanPayload.ComponentPayload> comps) {
        StringBuilder sb = new StringBuilder();
        for (ScanPayload.ComponentPayload c : comps) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(c.getName()).append('@').append(c.getVersion());
        }
        return sb.toString();
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + label);
        return ok ? 0 : 1;
    }
}
