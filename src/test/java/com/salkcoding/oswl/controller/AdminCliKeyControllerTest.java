package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.api.AdminCliKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.GlobalApiKeyResponse;
import com.salkcoding.oswl.service.ApiKeyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCliKeyController 단위 테스트")
class AdminCliKeyControllerTest {

    @Mock ApiKeyService   apiKeyService;
    @Mock AuditLogService auditLogService;

    @InjectMocks AdminCliKeyController controller;

    private Project project(Long id, String name) {
        return Project.builder().id(id).name(name).build();
    }

    private ApiKey apiKey(Long id, String token, String label, boolean active, Project project) {
        return ApiKey.builder()
                .id(id)
                .token(token)
                .label(label)
                .active(active)
                .project(project)
                .createdAt(LocalDateTime.now())
                .build();
    }

    // ── listAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listAll: 전체 키 목록 반환")
    void listAll_returnsAllKeys() {
        Project p1 = project(1L, "Project Alpha");
        Project p2 = project(2L, "Project Beta");
        List<ApiKey> keys = List.of(
                apiKey(10L, "abc123def456ghi789jkl", "CLI Key", true, p1),
                apiKey(11L, "xyz789uvw456rst123qpo", "Dev Key", false, p2)
        );
        when(apiKeyService.findAll()).thenReturn(keys);

        ResponseEntity<List<GlobalApiKeyResponse>> resp = controller.listAll();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0).getProjectName()).isEqualTo("Project Alpha");
        assertThat(resp.getBody().get(1).getProjectName()).isEqualTo("Project Beta");
    }

    @Test
    @DisplayName("listAll: 빈 목록 반환")
    void listAll_emptyList() {
        when(apiKeyService.findAll()).thenReturn(List.of());

        ResponseEntity<List<GlobalApiKeyResponse>> resp = controller.listAll();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("listAll: 토큰이 짧으면 *** 마스킹")
    void listAll_shortToken_masksToThreeStars() {
        Project p = project(1L, "P1");
        when(apiKeyService.findAll()).thenReturn(List.of(apiKey(1L, "short", "lbl", true, p)));

        ResponseEntity<List<GlobalApiKeyResponse>> resp = controller.listAll();

        assertThat(resp.getBody().get(0).getToken()).isEqualTo("***");
    }

    @Test
    @DisplayName("listAll: lastUsedAt null → lastUsedAt=null 포함")
    void listAll_nullLastUsedAt_includesNullString() {
        Project p = project(1L, "P1");
        ApiKey key = ApiKey.builder()
                .id(1L)
                .token("abc123def456ghi789jkl")
                .label("Key")
                .active(true)
                .project(p)
                .createdAt(LocalDateTime.now())
                .lastUsedAt(null)
                .build();
        when(apiKeyService.findAll()).thenReturn(List.of(key));

        ResponseEntity<List<GlobalApiKeyResponse>> resp = controller.listAll();

        assertThat(resp.getBody().get(0).getLastUsedAt()).isNull();
    }

    // ── issue ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue: projectId null → 400 BadRequest")
    void issue_nullProjectId_returns400() {
        AdminCliKeyIssueRequest req = new AdminCliKeyIssueRequest();
        req.setProjectId(null);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(apiKeyService, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("issue: 유효한 projectId, label null → 'CLI Key' 기본값")
    void issue_nullLabel_usesDefaultLabel() {
        Project p = project(5L, "My Project");
        ApiKey key = apiKey(20L, "tok123456789012345678", null, true, p);
        AdminCliKeyIssueRequest req = new AdminCliKeyIssueRequest();
        req.setProjectId(5L);
        req.setLabel(null);

        when(apiKeyService.issue(5L, "CLI Key", null)).thenReturn(key);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(apiKeyService).issue(5L, "CLI Key", null);
        verify(auditLogService).log(eq("CLI_KEY.CREATE"), eq("CLI_KEY"), any(), any(), isNull());
    }

    @Test
    @DisplayName("issue: label 있음 → 해당 label 사용")
    void issue_withLabel_usesProvidedLabel() {
        Project p = project(6L, "Service");
        ApiKey key = apiKey(21L, "tok123456789012345678", "MyLabel", true, p);
        AdminCliKeyIssueRequest req = new AdminCliKeyIssueRequest();
        req.setProjectId(6L);
        req.setLabel("MyLabel");

        when(apiKeyService.issue(6L, "MyLabel", null)).thenReturn(key);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getLabel()).isEqualTo("MyLabel");
        assertThat(resp.getBody().getMessage()).contains("securely");
    }

    @Test
    @DisplayName("issue: label blank → 'CLI Key' 기본값")
    void issue_blankLabel_usesDefaultLabel() {
        Project p = project(7L, "Blank Label Test");
        ApiKey key = apiKey(22L, "tok123456789012345678", null, true, p);
        AdminCliKeyIssueRequest req = new AdminCliKeyIssueRequest();
        req.setProjectId(7L);
        req.setLabel("   ");

        when(apiKeyService.issue(7L, "CLI Key", null)).thenReturn(key);

        controller.issue(req);

        verify(apiKeyService).issue(7L, "CLI Key", null);
    }

    // ── toggle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toggle: 키 활성화 → CLI_KEY.ACTIVATE 로그")
    void toggle_activatesKey_logsActivate() {
        Project p = project(1L, "P1");
        ApiKey key = apiKey(30L, "tok123456789012345678", "MyKey", true, p);
        when(apiKeyService.toggleActive(30L)).thenReturn(key);

        ResponseEntity<Void> resp = controller.toggle(30L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(auditLogService).log(eq("CLI_KEY.ACTIVATE"), eq("CLI_KEY"), any(), any(), isNull());
    }

    @Test
    @DisplayName("toggle: 키 비활성화 → CLI_KEY.REVOKE 로그")
    void toggle_revokesKey_logsRevoke() {
        Project p = project(1L, "P1");
        ApiKey key = ApiKey.builder()
                .id(31L)
                .token("tok123456789012345678")
                .label("Key")
                .project(p)
                .createdAt(LocalDateTime.now())
                .build();
        // active defaults to true; revoke it
        key.revoke();
        when(apiKeyService.toggleActive(31L)).thenReturn(key);

        ResponseEntity<Void> resp = controller.toggle(31L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(auditLogService).log(eq("CLI_KEY.REVOKE"), eq("CLI_KEY"), any(), any(), isNull());
    }

    @Test
    @DisplayName("toggle: label null인 경우 빈 문자열로 처리")
    void toggle_nullLabel_handledAsEmpty() {
        Project p = project(2L, "P2");
        ApiKey key = ApiKey.builder()
                .id(32L)
                .token("tok123456789012345678")
                .label(null)
                .active(true)
                .project(p)
                .createdAt(LocalDateTime.now())
                .build();
        when(apiKeyService.toggleActive(32L)).thenReturn(key);

        ResponseEntity<Void> resp = controller.toggle(32L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }
}
