package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.BitbucketCloudClient;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ScanResultRepository;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("NuGet static parse verification — dotnet/maui clone")
class MauiNuGetParseVerificationTest {

  private static final Path MAUI_CLONE = Path.of(
      System.getProperty("java.io.tmpdir"), "oswl-verify", "dotnet_maui");

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

  @InjectMocks QuickImportService service;

  @Test
  @DisplayName("static NuGet parse finds many more packages than the old depth-3/20-file limit")
  void staticParse_findsMostCsprojPackages() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(MAUI_CLONE),
        "Skip: clone dotnet/maui to " + MAUI_CLONE);

    Method m = QuickImportService.class.getDeclaredMethod("parseNuGetStatic", Path.class, String.class);
    m.setAccessible(true);
    Object result = m.invoke(service, MAUI_CLONE, "dotnet/maui");
    @SuppressWarnings("unchecked")
    List<ScanPayload.ComponentPayload> comps = extractComponents(result);

    long withVersion = comps.stream()
        .filter(c -> c.getVersion() != null && !c.getVersion().isBlank())
        .count();
    System.out.printf("[NuGetVerify] maui static: %d packages (%d with version)%n",
        comps.size(), withVersion);

    assertThat(comps.size()).isGreaterThan(50);
  }

  @SuppressWarnings("unchecked")
  private static List<ScanPayload.ComponentPayload> extractComponents(Object parsedDeps) throws Exception {
    var components = parsedDeps.getClass().getDeclaredField("components");
    components.setAccessible(true);
    return (List<ScanPayload.ComponentPayload>) components.get(parsedDeps);
  }
}
