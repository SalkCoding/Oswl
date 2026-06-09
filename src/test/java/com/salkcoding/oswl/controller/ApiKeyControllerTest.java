package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.dto.api.ApiKeyIssueRequest;
import com.salkcoding.oswl.dto.api.ApiKeyIssueResponse;
import com.salkcoding.oswl.dto.api.ApiKeyResponse;
import com.salkcoding.oswl.service.ApiKeyService;
import com.salkcoding.oswl.service.ApiKeyTokenSupport;
import com.salkcoding.oswl.service.IssuedApiKey;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectCliKeyPolicyService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyController 단위 테스트")
class ApiKeyControllerTest {

    @Mock ApiKeyService apiKeyService;
    @Mock ProjectCliKeyPolicyService projectCliKeyPolicyService;
    @Mock ProjectAccessService projectAccessService;

    @InjectMocks ApiKeyController controller;

    private ApiKey buildKey(Long id, String plainToken, String label, boolean active,
                            LocalDateTime created, LocalDateTime lastUsed, LocalDateTime revoked) {
        String prefix = plainToken == null ? null
                : (plainToken.length() >= ApiKeyTokenSupport.PREFIX_LENGTH
                ? plainToken.substring(0, ApiKeyTokenSupport.PREFIX_LENGTH)
                : plainToken);
        return ApiKey.builder()
                .id(id)
                .tokenPrefix(prefix)
                .tokenHash("bcrypt-hash-stub")
                .label(label)
                .active(active)
                .createdAt(created)
                .lastUsedAt(lastUsed)
                .revokedAt(revoked)
                .build();
    }

    private static IssuedApiKey issued(ApiKey key, String plainToken) {
        return new IssuedApiKey(key, plainToken);
    }

    // ── list ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list: 키가 없으면 빈 목록 반환")
    void list_empty_returnsEmptyList() {
        when(apiKeyService.findByProject(1L)).thenReturn(List.of());

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEmpty();
    }

    @Test
    @DisplayName("list: 키 목록 반환 + 토큰 마스킹")
    void list_withKeys_returnsAllMasked() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = buildKey(1L, "oswl_ABCDEFGHI123456JKLMNOP_last", "CI", true, now, null, null);
        when(apiKeyService.findByProject(2L)).thenReturn(List.of(key));

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(2L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(1);
        ApiKeyResponse r = resp.getBody().get(0);
        assertThat(r.getToken()).isEqualTo("oswl_ABCDEFGHI12...");
        assertThat(r.isActive()).isTrue();
        assertThat(r.getLabel()).isEqualTo("CI");
    }

    @Test
    @DisplayName("list: 토큰이 14자 미만이면 *** 마스킹")
    void list_shortToken_masksToStars() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = buildKey(2L, "short", "Test", true, now, null, null);
        when(apiKeyService.findByProject(1L)).thenReturn(List.of(key));

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(1L);

        assertThat(resp.getBody().get(0).getToken()).isEqualTo("***");
    }

    @Test
    @DisplayName("list: null 토큰은 *** 마스킹")
    void list_nullToken_masksToStars() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = buildKey(3L, null, "Null Token", true, now, null, null);
        when(apiKeyService.findByProject(1L)).thenReturn(List.of(key));

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(1L);

        assertThat(resp.getBody().get(0).getToken()).isEqualTo("***");
    }

    @Test
    @DisplayName("list: lastUsedAt null이면 응답에 null 포함")
    void list_nullLastUsedAt_returnsNullInResponse() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = buildKey(1L, "oswl_ABCDEFGHI123456JKLMNOP", "Dev", true, now, null, null);
        when(apiKeyService.findByProject(1L)).thenReturn(List.of(key));

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(1L);

        assertThat(resp.getBody().get(0).getLastUsedAt()).isNull();
    }

    @Test
    @DisplayName("list: revokedAt 있는 키 응답에 revokedAt 포함")
    void list_revokedKey_containsRevokedAt() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey key = buildKey(1L, "oswl_ABCDEFGHI123456JKLMNOP", "CI", false, now, null, now);
        when(apiKeyService.findByProject(5L)).thenReturn(List.of(key));

        ResponseEntity<List<ApiKeyResponse>> resp = controller.list(5L);

        assertThat(resp.getBody().get(0).getRevokedAt()).isNotNull();
        assertThat(resp.getBody().get(0).isActive()).isFalse();
    }

    // ── issue ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue: 새 키 발급 → 200 + 풀 토큰 반환")
    void issue_validRequest_returnsFullToken() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey newKey = buildKey(10L, "oswl_FULLTOKEN123456ABCDEF", "Pipeline", true, now, null, null);
        when(apiKeyService.issue(1L, "Pipeline", null)).thenReturn(issued(newKey, "oswl_FULLTOKEN123456ABCDEF"));

        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setLabel("Pipeline");
        req.setExpiresAt(null);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(1L, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getToken()).isEqualTo("oswl_FULLTOKEN123456ABCDEF");
        assertThat(resp.getBody().getId()).isEqualTo(10L);
        assertThat(resp.getBody().getLabel()).isEqualTo("Pipeline");
        assertThat(resp.getBody().getMessage()).contains("securely");
    }

    @Test
    @DisplayName("issue: null 레이블이면 응답 label에 빈 문자열")
    void issue_nullLabel_returnsEmptyLabel() {
        LocalDateTime now = LocalDateTime.now();
        ApiKey newKey = buildKey(11L, "oswl_FULLTOKEN123456ABCDEF", null, true, now, null, null);
        when(apiKeyService.issue(2L, null, null)).thenReturn(issued(newKey, "oswl_FULLTOKEN123456ABCDEF"));

        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setLabel(null);
        req.setExpiresAt(null);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(2L, req);

        assertThat(resp.getBody().getLabel()).isEqualTo("");
    }

    @Test
    @DisplayName("issue: expiresAt 지정 시 서비스에 전달")
    void issue_withExpiry_passesExpiryToService() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiry = now.plusYears(1);
        ApiKey newKey = buildKey(12L, "oswl_FULLTOKEN123456ABCDEF", "Expiring", true, now, null, null);
        when(apiKeyService.issue(3L, "Expiring", expiry)).thenReturn(issued(newKey, "oswl_FULLTOKEN123456ABCDEF"));

        ApiKeyIssueRequest req = new ApiKeyIssueRequest();
        req.setLabel("Expiring");
        req.setExpiresAt(expiry);

        ResponseEntity<ApiKeyIssueResponse> resp = controller.issue(3L, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(apiKeyService).issue(3L, "Expiring", expiry);
    }

    // ── revoke ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("revoke: 정상 호출 → 204")
    void revoke_success_returns204() {
        doNothing().when(apiKeyService).revoke(99L, 1L);

        ResponseEntity<Void> resp = controller.revoke(1L, 99L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(projectAccessService).assertCanViewProject(1L);
        verify(apiKeyService).revoke(99L, 1L);
    }

    @Test
    @DisplayName("revoke: 다른 프로젝트ID도 정상 전달")
    void revoke_differentProject_passesCorrectIds() {
        doNothing().when(apiKeyService).revoke(77L, 55L);

        controller.revoke(55L, 77L);

        verify(projectAccessService).assertCanViewProject(55L);
        verify(apiKeyService).revoke(77L, 55L);
    }
}
