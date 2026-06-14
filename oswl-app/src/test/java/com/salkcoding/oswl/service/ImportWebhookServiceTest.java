package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.ForbiddenException;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("ImportWebhookService")
class ImportWebhookServiceTest {

    private static final String SECRET = "whsec-test";
    private static final String REPO = "acme/demo";
    private static final String BODY = """
            {"ref":"refs/heads/main","repository":{"full_name":"acme/demo"}}
            """;

    @Mock ProjectRepository projectRepository;
    @Mock QuickImportService quickImportService;
    @Mock AuditLogService auditLogService;
    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @InjectMocks ImportWebhookService service;

    @Test
    @DisplayName("empty body is rejected")
    void emptyBody_rejected() {
        ImportWebhookService.WebhookResult result = service.handlePush("  ", Map.of());

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo("Empty body");
    }

    @Test
    @DisplayName("non-push GitHub event is ignored")
    void githubNonPush_ignored() {
        Map<String, String> headers = Map.of("X-GitHub-Event", "ping");

        ImportWebhookService.WebhookResult result = service.handlePush(BODY, headers);

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).contains("Ignored event");
    }

    @Test
    @DisplayName("invalid signature throws ForbiddenException")
    void invalidSignature_forbidden() {
        Project project = webhookProject();
        when(projectRepository.findByGithubRepoAndDeletedAtIsNull(REPO)).thenReturn(Optional.of(project));

        Map<String, String> headers = Map.of(
                "X-GitHub-Event", "push",
                "X-Hub-Signature-256", "sha256=deadbeef");

        assertThatThrownBy(() -> service.handlePush(BODY, headers))
                .isInstanceOf(ForbiddenException.class);
        verify(auditLogService).logAnonymous(eq("webhook"), eq("IMPORT.WEBHOOK_AUTH_FAILURE"),
                eq("PROJECT"), eq("1"), eq(REPO), anyString());
    }

    @Test
    @DisplayName("valid GitHub push queues Quick Import")
    void validGithubPush_queuesImport() {
        Project project = webhookProject();
        when(projectRepository.findByGithubRepoAndDeletedAtIsNull(REPO)).thenReturn(Optional.of(project));
        when(quickImportService.startImport("https://github.com/acme/demo", "main", 42L))
                .thenReturn("job-99");

        Map<String, String> headers = Map.of(
                "X-GitHub-Event", "push",
                "X-Hub-Signature-256", githubSignature(SECRET, BODY));

        ImportWebhookService.WebhookResult result = service.handlePush(BODY, headers);

        assertThat(result.accepted()).isTrue();
        assertThat(result.jobId()).isEqualTo("job-99");
        verify(quickImportService).startImport("https://github.com/acme/demo", "main", 42L);
    }

    private Project webhookProject() {
        return Project.builder()
                .id(1L)
                .githubRepo(REPO)
                .vcsProvider(VcsProvider.GITHUB)
                .createdByUserId(42L)
                .webhookEnabled(true)
                .webhookSecret(SECRET)
                .build();
    }

    private static String githubSignature(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
