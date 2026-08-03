package com.salkcoding.oswl.local;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService;
import com.salkcoding.oswl.service.ingest.MavenBomVersionResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Throwaway sanity check for composer.lock / conan.lock parsing.
 * Not part of the build or test suite — compiled and run manually, then deleted.
 */
public final class LockParserSanityCheck {

    private LockParserSanityCheck() {}

    static void main(String[] args) throws Exception {
        DependencyManifestParserService parser =
                new DependencyManifestParserService(new MavenBomVersionResolver());
        int failures = 0;

        // ── 1. Repo-walk parse: composer.lock + conan.lock (2.x) in one tree ──
        Path composerDir = Path.of(args[0], "composer");
        Path conan2Dir   = Path.of(args[0], "conan2");
        Path conan1Dir   = Path.of(args[0], "conan1");

        var composerResult = parser.parseDependencies(composerDir, "composer-fixture");
        failures += check("composer ecosystem", "COMPOSER".equals(composerResult.ecosystem()));
        failures += check("composer count > 50", composerResult.components().size() > 50);
        ScanPayload.ComponentPayload caBundle = find(composerResult.components(), "composer/ca-bundle");
        failures += check("composer/ca-bundle present", caBundle != null);
        failures += check("composer/ca-bundle ecosystem COMPOSER",
                caBundle != null && "COMPOSER".equals(caBundle.getEcosystem()));
        failures += check("composer/ca-bundle version no leading v",
                caBundle != null && caBundle.getVersion() != null && !caBundle.getVersion().startsWith("v"));
        long devCount = composerResult.components().stream()
                .filter(c -> "dev".equals(c.getScope())).count();
        failures += check("composer dev-scope entries exist (packages-dev tagged)", devCount > 0);
        long runtimeCount = composerResult.components().stream().filter(c -> c.getScope() == null).count();
        failures += check("composer runtime entries exist (scope null)", runtimeCount > 0);
        System.out.printf("  [composer] total=%d runtime=%d dev=%d sample=%s@%s scope=%s%n",
                composerResult.components().size(), runtimeCount, devCount,
                caBundle != null ? caBundle.getName() : "?",
                caBundle != null ? caBundle.getVersion() : "?",
                caBundle != null ? caBundle.getScope() : "?");

        var conan2Result = parser.parseDependencies(conan2Dir, "conan2-fixture");
        failures += check("conan2 ecosystem", "CONAN".equals(conan2Result.ecosystem()));
        failures += check("conan2 count == 4", conan2Result.components().size() == 4);
        ScanPayload.ComponentPayload fmt = find(conan2Result.components(), "fmt");
        failures += check("conan2 fmt version 9.1.0 (revision/timestamp stripped)",
                fmt != null && "9.1.0".equals(fmt.getVersion()));
        ScanPayload.ComponentPayload boost = find(conan2Result.components(), "boost");
        failures += check("conan2 boost user/channel stripped",
                boost != null && "1.83.0".equals(boost.getVersion()));
        ScanPayload.ComponentPayload cmake = find(conan2Result.components(), "cmake");
        failures += check("conan2 cmake build_requires tagged dev",
                cmake != null && "dev".equals(cmake.getScope()));
        failures += check("conan2 fmt runtime scope null", fmt != null && fmt.getScope() == null);
        System.out.printf("  [conan2] %s%n", describe(conan2Result.components()));

        var conan1Result = parser.parseDependencies(conan1Dir, "conan1-fixture");
        failures += check("conan1 ecosystem", "CONAN".equals(conan1Result.ecosystem()));
        failures += check("conan1 count == 3 (app, fmt, openssl; path node skipped)",
                conan1Result.components().size() == 3);
        ScanPayload.ComponentPayload openssl = find(conan1Result.components(), "openssl");
        failures += check("conan1 openssl/3.1.2 parsed",
                openssl != null && "3.1.2".equals(openssl.getVersion()));
        System.out.printf("  [conan1] %s%n", describe(conan1Result.components()));

        // ── 2. Upload path: parseUploadedLockFile by content shape ────────────
        byte[] composerBytes = Files.readAllBytes(composerDir.resolve("composer.lock"));
        List<ScanPayload.ComponentPayload> uploadedComposer =
                parser.parseUploadedLockFile(composerBytes, "upload-composer");
        failures += check("upload composer.lock recognized",
                uploadedComposer != null && uploadedComposer.size() == composerResult.components().size());

        byte[] conan2Bytes = Files.readAllBytes(conan2Dir.resolve("conan.lock"));
        List<ScanPayload.ComponentPayload> uploadedConan =
                parser.parseUploadedLockFile(conan2Bytes, "upload-conan");
        failures += check("upload conan.lock recognized",
                uploadedConan != null && uploadedConan.size() == 4);

        byte[] conan1Bytes = Files.readAllBytes(conan1Dir.resolve("conan.lock"));
        List<ScanPayload.ComponentPayload> uploadedConan1 =
                parser.parseUploadedLockFile(conan1Bytes, "upload-conan1");
        failures += check("upload conan 1.x lock recognized",
                uploadedConan1 != null && uploadedConan1.size() == 3);

        // CycloneDX JSON must NOT be detected as a lock file (falls back to SBOM path)
        byte[] cycloneDx = ("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
                + "\"components\":[]}").getBytes();
        failures += check("CycloneDX JSON not misdetected as lock file",
                parser.parseUploadedLockFile(cycloneDx, "upload-sbom") == null);
        // package-lock.json must NOT be misdetected as composer.lock
        byte[] npmLock = ("{\"name\":\"x\",\"version\":\"1.0.0\",\"lockfileVersion\":3,"
                + "\"requires\":true,\"packages\":{\"\":{\"name\":\"x\"}}}").getBytes();
        failures += check("package-lock.json not misdetected as composer.lock",
                parser.parseUploadedLockFile(npmLock, "upload-npm") == null);
        failures += check("garbage returns null",
                parser.parseUploadedLockFile("not json at all".getBytes(), "upload-garbage") == null);

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
            sb.append(c.getName()).append('@').append(c.getVersion())
              .append(c.getScope() != null ? "[" + c.getScope() + "]" : "");
        }
        return sb.toString();
    }

    private static int check(String label, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + label);
        return ok ? 0 : 1;
    }
}
