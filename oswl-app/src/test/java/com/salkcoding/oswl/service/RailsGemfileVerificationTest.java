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
@DisplayName("Ruby Gemfile.lock verification — rails/rails clone")
class RailsGemfileVerificationTest {

  private static final Path RAILS_CLONE = Path.of(
      System.getProperty("java.io.tmpdir"), "oswl-verify", "rails_rails");

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
  @DisplayName("Gemfile.lock parser captures most gems including pre-release versions")
  void gemfileLock_parsesRailsRootLock() throws Exception {
    Assumptions.assumeTrue(Files.isDirectory(RAILS_CLONE),
        "Skip: clone rails/rails to " + RAILS_CLONE);

    Method m = DependencyManifestParserService.class.getDeclaredMethod("parseGemfileLock", Path.class, String.class);
    m.setAccessible(true);

    int total = 0;
    for (Path lock : Files.walk(RAILS_CLONE)
        .filter(p -> p.getFileName().toString().equals("Gemfile.lock"))
        .toList()) {
      @SuppressWarnings("unchecked")
      List<ScanPayload.ComponentPayload> comps =
          (List<ScanPayload.ComponentPayload>) m.invoke(service, lock.getParent(), "rails/rails");
      if (comps != null) {
        total += comps.size();
      }
    }

    System.out.printf("[RubyVerify] rails Gemfile.lock total gems parsed: %d%n", total);

    assertThat(total).isGreaterThanOrEqualTo(250);
    @SuppressWarnings("unchecked")
    List<ScanPayload.ComponentPayload> root =
        (List<ScanPayload.ComponentPayload>) m.invoke(service, RAILS_CLONE, "rails/rails");
    assertThat(root).extracting(ScanPayload.ComponentPayload::getName).contains("actioncable");
    assertThat(root).filteredOn(c -> "actioncable".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .anyMatch(v -> v != null && v.contains("alpha"));
  }
}
