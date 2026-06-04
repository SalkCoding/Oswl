package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.entity.UserVcsConnection;
import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.client.BitbucketCloudClient;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.exception.QuickImportUpstreamException;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus.Phase;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QuickImportService 단위 테스트 (공개 API)")
class QuickImportServiceTest {

    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @Mock EncryptionService            encryptionService;
    @Mock ProjectService               projectService;
    @Mock ApiKeyService                apiKeyService;
    @Mock ScanIngestService            scanIngestService;
    @Mock ScanResultRepository         scanResultRepository;
    @Mock GitHubService                gitHubService;
    @Mock EnrichmentProgressHolder     enrichmentProgressHolder;
    @Mock MavenBomVersionResolver      bomVersionResolver;
    @Mock ProjectCliKeyPolicyService   projectCliKeyPolicyService;
    @Mock BitbucketCloudClient        bitbucketCloudClient;
    @Mock com.salkcoding.oswl.service.git.GitCloneExecutor gitCloneExecutor;
    @Mock com.salkcoding.oswl.auth.service.AuditLogService auditLogService;
    @Mock org.springframework.context.MessageSource messageSource;

    @InjectMocks QuickImportService quickImportService;

    @org.junit.jupiter.api.BeforeEach
    void stubCliKeyPolicy() {
        lenient().when(projectCliKeyPolicyService.resolve(anyLong()))
                .thenReturn(ProjectCliKeyPolicyService.ProjectKeyState.NONE);
        lenient().doNothing().when(projectCliKeyPolicyService).assertScanIngestAllowed(anyLong());
    }

    // ── getJobStatus ──────────────────────────────────────────────────────

    @Test
    @DisplayName("getJobStatus: 알 수 없는 jobId는 null을 반환한다")
    void getJobStatus_unknownId_returnsNull() {
        assertThat(quickImportService.getJobStatus("unknown-id", 1L)).isNull();
    }

    @Test
    @DisplayName("getJobStatus: 다른 사용자 job은 null을 반환한다 (IDOR 방지)")
    void getJobStatus_wrongOwner_returnsNull() {
        String jobId = quickImportService.startImport("https://github.com/user/repo", "main", 10L);
        assertThat(quickImportService.getJobStatus(jobId, 99L)).isNull();
    }

    // ── startImport ───────────────────────────────────────────────────────

    @Test
    @DisplayName("startImport: 새 작업을 시작하면 UUID 형식의 jobId를 반환한다")
    void startImport_returnsJobId() {
        String jobId = quickImportService.startImport("https://github.com/user/repo", "main", 1L);
        assertThat(jobId).isNotNull().isNotBlank();
        // Should match UUID pattern
        assertThat(jobId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("startImport: 동일 user가 연속으로 시작해도 jobId는 각각 새로 발급된다")
    void startImport_activeJobExists_createsDistinctJobIds() {
        String jobId1 = quickImportService.startImport("https://github.com/user/repo", "main", 2L);
        String jobId2 = quickImportService.startImport("https://github.com/user/repo2", "dev", 2L);

        assertThat(jobId2).isNotEqualTo(jobId1);
        assertThat(quickImportService.listJobsForUser(2L)).hasSize(2);
    }

    @Test
    @DisplayName("startImport 후 getJobStatus: QUEUED 또는 그 이후 상태로 시작된다")
    void startImport_then_getJobStatus_notNull() {
        String jobId = quickImportService.startImport("https://github.com/user/repo", "main", 3L);

        // Wait briefly for the virtual thread to possibly change state
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        // getJobStatus may return QUEUED, FAILED, or any intermediate state
        // but it should NOT be null since the job was registered
        QuickImportJobStatus status = quickImportService.getJobStatus(jobId, 3L);
        assertThat(status).isNotNull();
        assertThat(status.getJobId()).isEqualTo(jobId);
    }

    @Test
    @DisplayName("getJobStatus: DONE 첫 폴링만 전체 토큰, 이후 마스킹")
    void getJobStatus_masksApiTokenAfterFirstDonePoll() {
        String jobId = "mask-test-job";
        String fullToken = "oswl_abcdefghijklmnopqrstuvwxyz123456";
        QuickImportJobStatus done = QuickImportJobStatus.builder()
                .jobId(jobId)
                .phase(Phase.DONE)
                .message("done")
                .projectId(1L)
                .projectName("proj")
                .apiToken(fullToken)
                .newApiKey(true)
                .ecosystem("MAVEN")
                .componentCount(5)
                .build();

        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, QuickImportJobStatus> jobs =
                (ConcurrentHashMap<String, QuickImportJobStatus>)
                        ReflectionTestUtils.getField(quickImportService, "jobs");
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Long> owners =
                (ConcurrentHashMap<String, Long>)
                        ReflectionTestUtils.getField(quickImportService, "jobOwners");
        jobs.put(jobId, done);
        owners.put(jobId, 1L);

        QuickImportJobStatus first = quickImportService.getJobStatus(jobId, 1L);
        assertThat(first.getApiToken()).isEqualTo(fullToken);

        QuickImportJobStatus second = quickImportService.getJobStatus(jobId, 1L);
        assertThat(second.getApiToken()).isEqualTo(QuickImportService.maskApiToken(fullToken));
    }

    // ── listRepos ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("listRepos: 해당 provider의 VCS 연결이 없으면 빈 목록 반환")
    void listRepos_noConnection_returnsEmpty() {
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITHUB))
                .thenReturn(Optional.empty());

        var result = quickImportService.listRepos(VcsProvider.GITHUB, 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listRepos: 토큰 복호화 실패 시 빈 목록 반환")
    void listRepos_decryptFails_returnsEmpty() throws Exception {
        UserVcsConnection conn = UserVcsConnection.builder()
                .id(1L).provider(VcsProvider.GITHUB).active(true)
                .accessTokenEncrypted("encrypted-token")
                .build();
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITHUB))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("encrypted-token")).thenThrow(new RuntimeException("bad key"));

        var result = quickImportService.listRepos(VcsProvider.GITHUB, 1L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listRepos: GitHubService 실패 시 IllegalStateException 전파")
    void listRepos_githubServiceFails_throwsIllegalState() throws Exception {
        UserVcsConnection conn = UserVcsConnection.builder()
                .id(1L).provider(VcsProvider.GITHUB).active(true)
                .accessTokenEncrypted("encrypted-token")
                .build();
        when(vcsConnectionRepository.findByUserIdAndProviderAndActiveTrue(1L, VcsProvider.GITHUB))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("encrypted-token")).thenReturn("valid-token");
        when(gitHubService.resolveWebBase(null)).thenReturn("https://github.com");
        when(gitHubService.listAllUserRepos("valid-token", null)).thenThrow(new RuntimeException("API error"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> quickImportService.listRepos(VcsProvider.GITHUB, 1L))
                .isInstanceOf(QuickImportUpstreamException.class);
    }

    // ── evictExpiredJobs ──────────────────────────────────────────────────

    @Test
    @DisplayName("evictExpiredJobs: 예외 없이 실행된다")
    void evictExpiredJobs_noException() {
        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> quickImportService.evictExpiredJobs());
    }
}
