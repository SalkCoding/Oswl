package com.salkcoding.oswl.local;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.CondaPypiMappingService;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService;
import com.salkcoding.oswl.service.ingest.MavenBomVersionResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Throwaway sanity check for composer.lock / conan.lock / Podfile.lock / conda lock-format
 * parsing. Not part of the build or test suite — compiled and run manually, then deleted.
 *
 * With no arguments only the fixture-free checks run (upload-path content detection plus a
 * temp-dir pixi.lock repo walk); pass a fixture root directory to also run the historical
 * composer/conan tree checks.
 */
public final class LockParserSanityCheck {

    private LockParserSanityCheck() {}

    static void main(String[] args) throws Exception {
        DependencyManifestParserService parser =
                new DependencyManifestParserService(new MavenBomVersionResolver(), new CondaPypiMappingService());
        int failures = 0;

        if (args.length > 0) {
            failures += runFixtureChecks(parser, args[0]);
        }

        failures += runUploadPathChecks(parser);
        failures += runPixiRepoWalkCheck(parser);

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ── Upload path: parseUploadedLockFile by content shape (no fixtures needed) ──
    private static int runUploadPathChecks(DependencyManifestParserService parser) {
        int failures = 0;

        // Podfile.lock (YAML-shaped, line-parsed)
        byte[] podfile = ("PODS:\n"
                + "  - Alamofire (5.6.4)\n"
                + "  - GoogleUtilities/Environment (7.11.0):\n"
                + "    - GoogleUtilities/Core\n"
                + "DEPENDENCIES:\n"
                + "  - Alamofire\n").getBytes();
        var pods = parser.parseUploadedLockFile(podfile, "upload-podfile");
        failures += check("upload Podfile.lock recognized", pods != null && pods.size() == 2);
        ScanPayload.ComponentPayload alamofire = pods != null ? find(pods, "Alamofire") : null;
        failures += check("upload Podfile.lock Alamofire 5.6.4 COCOAPODS",
                alamofire != null && "5.6.4".equals(alamofire.getVersion())
                        && "COCOAPODS".equals(alamofire.getEcosystem()));
        ScanPayload.ComponentPayload gu = pods != null ? find(pods, "GoogleUtilities") : null;
        failures += check("upload Podfile.lock subspec folded into base pod",
                gu != null && "7.11.0".equals(gu.getVersion()));

        // conda-lock.yml (YAML with a flat "package" list)
        byte[] condaLock = ("version: 1\n"
                + "metadata:\n  content_hash: {}\n"
                + "channels: []\nplatforms: []\n"
                + "package:\n"
                + "  - name: openssl\n    version: 1.1.1k\n    manager: conda\n"
                + "  - name: requests\n    version: 2.31.0\n    manager: pip\n").getBytes();
        var condaComps = parser.parseUploadedLockFile(condaLock, "upload-condalock");
        failures += check("upload conda-lock.yml recognized", condaComps != null && condaComps.size() == 2);
        ScanPayload.ComponentPayload requests = condaComps != null ? find(condaComps, "requests") : null;
        failures += check("upload conda-lock pip entry stays PYPI",
                requests != null && "PYPI".equals(requests.getEcosystem())
                        && "2.31.0".equals(requests.getVersion()));
        ScanPayload.ComponentPayload opensslConda = condaComps != null ? find(condaComps, "openssl") : null;
        failures += check("upload conda-lock conda entry tagged CONDA or mapped PYPI",
                opensslConda != null && "1.1.1k".equals(opensslConda.getVersion())
                        && ("CONDA".equals(opensslConda.getEcosystem()) || "PYPI".equals(opensslConda.getEcosystem())));

        // pixi.lock (YAML: environments → per-platform package URLs)
        byte[] pixiLock = pixiLockBytes();
        var pixiComps = parser.parseUploadedLockFile(pixiLock, "upload-pixi");
        failures += check("upload pixi.lock recognized", pixiComps != null && pixiComps.size() == 2);
        ScanPayload.ComponentPayload pixiOpenssl = pixiComps != null ? find(pixiComps, "openssl") : null;
        failures += check("upload pixi.lock conda URL filename -> openssl 3.1.1",
                pixiOpenssl != null && "3.1.1".equals(pixiOpenssl.getVersion()));
        ScanPayload.ComponentPayload pixiRequests = pixiComps != null ? find(pixiComps, "requests") : null;
        failures += check("upload pixi.lock pypi entry PYPI 2.31.0",
                pixiRequests != null && "PYPI".equals(pixiRequests.getEcosystem())
                        && "2.31.0".equals(pixiRequests.getVersion()));

        // conda list --explicit spec (no stable filename — content shape only)
        byte[] explicit = ("# This file may be used to create an environment using:\n"
                + "# $ conda create --name myenv --file <this file>\n"
                + "# platform: linux-64\n"
                + "@EXPLICIT\n"
                + "https://conda.anaconda.org/conda-forge/linux-64/openssl-1.1.1k-hd590300_0.tar.bz2\n"
                + "https://conda.anaconda.org/conda-forge/linux-64/_libgcc_mutex-0.1-conda_forge.tar.bz2\n").getBytes();
        var explicitComps = parser.parseUploadedLockFile(explicit, "upload-explicit");
        failures += check("upload conda explicit spec recognized",
                explicitComps != null && explicitComps.size() == 2);
        ScanPayload.ComponentPayload explicitOpenssl = explicitComps != null ? find(explicitComps, "openssl") : null;
        failures += check("upload explicit openssl 1.1.1k",
                explicitOpenssl != null && "1.1.1k".equals(explicitOpenssl.getVersion()));
        ScanPayload.ComponentPayload libgcc = explicitComps != null ? find(explicitComps, "_libgcc_mutex") : null;
        failures += check("upload explicit leading-underscore name parsed",
                libgcc != null && "0.1".equals(libgcc.getVersion()));

        // Misdetection guards
        failures += check("CycloneDX JSON not misdetected as lock file",
                parser.parseUploadedLockFile("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[]}".getBytes(), "upload-sbom") == null);
        failures += check("package-lock.json not misdetected as composer.lock",
                parser.parseUploadedLockFile("{\"name\":\"x\",\"version\":\"1.0.0\",\"lockfileVersion\":3,\"requires\":true,\"packages\":{\"\":{\"name\":\"x\"}}}".getBytes(), "upload-npm") == null);
        failures += check("CycloneDX XML not misdetected as lock file",
                parser.parseUploadedLockFile("<?xml version=\"1.0\"?><bom xmlns=\"http://cyclonedx.org/schema/bom/1.6\"></bom>".getBytes(), "upload-xml") == null);
        failures += check("random YAML not misdetected",
                parser.parseUploadedLockFile("foo: bar\nbaz: 1\n".getBytes(), "upload-yaml") == null);
        failures += check("pnpm-lock.yaml not misdetected as conda-lock",
                parser.parseUploadedLockFile("lockfileVersion: '9.0'\npackages:\n  /lodash@4.17.21:\n    resolution: {}\n".getBytes(), "upload-pnpm") == null);
        failures += check("garbage returns null",
                parser.parseUploadedLockFile("not json at all".getBytes(), "upload-garbage") == null);
        return failures;
    }

    // ── Repo-walk path: pixi.lock in a temp tree (exercises ManifestCollectRules wiring) ──
    private static int runPixiRepoWalkCheck(DependencyManifestParserService parser) throws Exception {
        int failures = 0;
        Path dir = Files.createTempDirectory("oswl-pixi-fixture");
        try {
            Files.write(dir.resolve("pixi.lock"), pixiLockBytes());
            var result = parser.parseDependencies(dir, "pixi-fixture");
            failures += check("repo-walk pixi.lock found", result.components().size() == 2);
            ScanPayload.ComponentPayload openssl = find(result.components(), "openssl");
            failures += check("repo-walk pixi.lock openssl 3.1.1",
                    openssl != null && "3.1.1".equals(openssl.getVersion()));
        } finally {
            Files.deleteIfExists(dir.resolve("pixi.lock"));
            Files.deleteIfExists(dir);
        }
        return failures;
    }

    private static byte[] pixiLockBytes() {
        return ("version: 6\n"
                + "environments:\n"
                + "  default:\n"
                + "    channels:\n"
                + "      - url: https://conda.anaconda.org/conda-forge/\n"
                + "    packages:\n"
                + "      linux-64:\n"
                + "        - conda: https://conda.anaconda.org/conda-forge/linux-64/openssl-3.1.1-hd590300_1.conda\n"
                + "        - pypi: https://files.pythonhosted.org/packages/xx/requests-2.31.0-py3-none-any.whl\n"
                + "          name: requests\n"
                + "          version: 2.31.0\n").getBytes();
    }

    private static int runFixtureChecks(DependencyManifestParserService parser, String fixtureRoot) throws Exception {
        int failures = 0;

        // ── 1. Repo-walk parse: composer.lock + conan.lock (2.x) in one tree ──
        Path composerDir = Path.of(fixtureRoot, "composer");
        Path conan2Dir   = Path.of(fixtureRoot, "conan2");
        Path conan1Dir   = Path.of(fixtureRoot, "conan1");

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

        return failures;
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
