package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.CreatePrRequest;
import com.salkcoding.oswl.dto.DeferralRequest;
import com.salkcoding.oswl.service.ComponentDetailService;
import com.salkcoding.oswl.service.SessionCipherService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComponentDetailController 단위 테스트")
class ComponentDetailControllerTest {

    @Mock ComponentDetailService componentDetailService;
    @Mock SessionCipherService   sessionCipher;
    @Mock HttpSession            session;
    @Mock Model                  model;

    @InjectMocks ComponentDetailController controller;

    private OswlUserPrincipal principal() {
        return new OswlUserPrincipal(
                1L, "user@test.com", "hash", "Test User",
                false, true, List.of(), Set.of(), Set.of(), false);
    }

    // ── detail ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("detail: 일반 요청 → index 뷰 반환")
    void detail_normalRequest_returnsIndexView() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        doNothing().when(componentDetailService).populateModel(1L, 10L, model);

        String view = controller.detail(1L, 10L, model, request);

        assertThat(view).isEqualTo("component-detail/index");
    }

    @Test
    @DisplayName("detail: HTMX 요청 → fragment 뷰 반환")
    void detail_htmxRequest_returnsFragment() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("HX-Request", "true");
        doNothing().when(componentDetailService).populateModel(1L, 10L, model);

        String view = controller.detail(1L, 10L, model, request);

        assertThat(view).isEqualTo("component-detail/fragments/detail-content :: content");
    }

    // ── defer ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("defer: 정상 호출 → 204")
    void defer_validRequest_returns204() {
        DeferralRequest req = new DeferralRequest();
        doNothing().when(componentDetailService).defer(1L, 10L, req);

        ResponseEntity<Void> resp = controller.defer(1L, 10L, req);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(componentDetailService).defer(1L, 10L, req);
    }

    // ── createPr ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("createPr: 성공 → 200 + PR URL 반환")
    void createPr_success_returns200() {
        when(session.getAttribute("githubTokens")).thenReturn(null);
        Map<String, Object> result = Map.of("prUrl", "https://github.com/pr/1");
        when(componentDetailService.createPullRequest(eq(1L), eq(10L), any(), eq(1L), isNull()))
                .thenReturn(result);
        CreatePrRequest req = new CreatePrRequest();

        ResponseEntity<Map<String, Object>> resp = controller.createPr(1L, 10L, req, session, principal());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("prUrl")).isEqualTo("https://github.com/pr/1");
    }

    @Test
    @DisplayName("createPr: IllegalStateException → 400")
    void createPr_illegalState_returns400() {
        when(session.getAttribute("githubTokens")).thenReturn(null);
        when(componentDetailService.createPullRequest(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("No VCS connection"));
        CreatePrRequest req = new CreatePrRequest();

        ResponseEntity<Map<String, Object>> resp = controller.createPr(1L, 10L, req, session, principal());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().get("error")).isEqualTo("No VCS connection");
    }

    @Test
    @DisplayName("createPr: Exception → 502")
    void createPr_genericException_returns502() {
        when(session.getAttribute("githubTokens")).thenReturn(null);
        when(componentDetailService.createPullRequest(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new RuntimeException("VCS network error"));
        CreatePrRequest req = new CreatePrRequest();

        ResponseEntity<Map<String, Object>> resp = controller.createPr(1L, 10L, req, session, principal());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody().get("error").toString()).contains("VCS error");
    }

    @Test
    @DisplayName("createPr: principal null → userId=null로 서비스 호출")
    void createPr_nullPrincipal_passesNullUserId() {
        when(session.getAttribute("githubTokens")).thenReturn(null);
        when(componentDetailService.createPullRequest(eq(1L), eq(10L), any(), isNull(), isNull()))
                .thenReturn(Map.of("prUrl", "https://github.com/pr/2"));
        CreatePrRequest req = new CreatePrRequest();

        ResponseEntity<Map<String, Object>> resp = controller.createPr(1L, 10L, req, session, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(componentDetailService).createPullRequest(eq(1L), eq(10L), any(), isNull(), isNull());
    }

    @Test
    @DisplayName("createPr: GitHub 토큰이 세션에 있으면 복호화해서 전달")
    void createPr_githubTokenInSession_decryptsAndPasses() {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("octocat", "encrypted");
        when(session.getAttribute("githubTokens")).thenReturn(tokens);
        when(sessionCipher.decrypt("encrypted")).thenReturn("plaintoken");
        when(componentDetailService.createPullRequest(eq(1L), eq(10L), any(), eq(1L), eq("plaintoken")))
                .thenReturn(Map.of("prUrl", "https://github.com/pr/3"));
        CreatePrRequest req = new CreatePrRequest();

        ResponseEntity<Map<String, Object>> resp = controller.createPr(1L, 10L, req, session, principal());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionCipher).decrypt("encrypted");
    }
}
