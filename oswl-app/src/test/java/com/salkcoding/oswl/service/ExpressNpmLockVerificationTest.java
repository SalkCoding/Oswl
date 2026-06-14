package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.BitbucketCloudClient;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
import com.salkcoding.oswl.service.manifest.NpmPackageJsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.PARSER)
@ExtendWith(MockitoExtension.class)
@DisplayName("npm lock generation verification — expressjs/express clone")
class ExpressNpmLockVerificationTest {

  private static final Path EXPRESS_CLONE = Path.of(
      System.getProperty("java.io.tmpdir"), "oswl-verify", "expressjs_express");

  @Mock UserVcsConnectionRepository vcsConnectionRepository;
  @Mock EncryptionService encryptionService;
  @Mock ProjectService projectService;
  @Mock ApiKeyService apiKeyService;
  @Mock ScanIngestService scanIngestService;
  @Mock ScanResultRepository scanResultRepository;
  @Mock GitHubService gitHubService;
  @Mock BitbucketCloudClient bitbucketCloudClient;
  @Mock EnrichmentProgressHolder enrichmentProgressHolder;
  @Mock MavenBomVersionResolver bomVersionResolver;
  @Mock GitCloneExecutor gitCloneExecutor;
  @Mock UserRepository userRepository;
  @Mock AuditLogService auditLogService;
  @Mock ProjectCliKeyPolicyService projectCliKeyPolicyService;
  @Mock org.springframework.context.MessageSource messageSource;

  @InjectMocks DependencyManifestParserService service;

  @Test
  @DisplayName("npm --package-lock-only yields transitive deps beyond package.json direct refs")
  void npmLockGeneration_includesTransitivePackages() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(EXPRESS_CLONE),
        "Skip: clone expressjs/express to " + EXPRESS_CLONE);
    Assumptions.assumeTrue(Files.exists(EXPRESS_CLONE.resolve("package.json")));
    Assumptions.assumeFalse(Files.exists(EXPRESS_CLONE.resolve("package-lock.json")),
        "Skip: remove package-lock.json to test generation");

    List<ScanPayload.ComponentPayload> directDeps = NpmPackageJsonParser.parse(EXPRESS_CLONE, "expressjs/express");

    Method lockGen = DependencyManifestParserService.class.getDeclaredMethod("runNpmPackageLockOnly", Path.class, String.class);
    lockGen.setAccessible(true);
    @SuppressWarnings("unchecked")
    List<ScanPayload.ComponentPayload> lockDeps =
        (List<ScanPayload.ComponentPayload>) lockGen.invoke(service, EXPRESS_CLONE, "expressjs/express");

    Assumptions.assumeTrue(lockDeps != null && !lockDeps.isEmpty(),
        "Skip: npm lock generation failed (network/npm required)");

    System.out.printf("[NpmVerify] express direct=%d lock-generated=%d%n",
        directDeps.size(), lockDeps.size());

    assertThat(lockDeps.size()).isGreaterThan(directDeps.size());
    Files.deleteIfExists(EXPRESS_CLONE.resolve("package-lock.json"));
  }
}
