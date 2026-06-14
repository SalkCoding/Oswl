package com.salkcoding.oswl.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MustChangePasswordFilter 단위 테스트")
class MustChangePasswordFilterTest {

    @Mock FilterChain filterChain;

    MustChangePasswordFilter filter = new MustChangePasswordFilter();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OswlUserPrincipal principal(boolean mustChange) {
        return new OswlUserPrincipal(
                1L, "user@test.com", "hash", "Test User",
                false, true, List.of(), Set.of(), Set.of(), mustChange);
    }

    private void setAuthentication(OswlUserPrincipal p) {
        var auth = new UsernamePasswordAuthenticationToken(p, null, p.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ── passthrough URIs (no redirect regardless of mustChange) ───────────

    @Test
    @DisplayName("change-password 경로: 패스스루")
    void doFilter_changePasswordUri_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/change-password");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("API change-password 경로: 패스스루")
    void doFilter_apiChangePasswordUri_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/change-password");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("logout 경로: 패스스루")
    void doFilter_logoutUri_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/logout");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("정적 자산(/css): 패스스루")
    void doFilter_cssPath_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/css/tailwind.css");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("정적 자산(/js): 패스스루")
    void doFilter_jsPath_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/js/app.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("favicon.ico: 패스스루")
    void doFilter_favicon_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/favicon.ico");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    // ── normal user (mustChange = false) ──────────────────────────────────

    @Test
    @DisplayName("mustChangePassword=false: 필터 통과")
    void doFilter_normalUser_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/projects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(false));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("인증 없음: 필터 통과")
    void doFilter_noAuthentication_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/projects");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    // ── mustChange = true, browser request → redirect ────────────────────

    @Test
    @DisplayName("mustChangePassword=true, HTML 요청: /change-password로 리다이렉트")
    void doFilter_mustChange_browserRequest_redirects() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/settings");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        assertThat(resp.getRedirectedUrl()).isEqualTo("/change-password");
        verify(filterChain, never()).doFilter(any(), any());
    }

    // ── mustChange = true, API request → 403 JSON ─────────────────────────

    @Test
    @DisplayName("mustChangePassword=true, /api 요청: 403 + JSON 바디")
    void doFilter_mustChange_apiRequest_returns403Json() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/projects");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(resp.getContentType()).contains("application/json");
        assertThat(resp.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
        assertThat(resp.getContentAsString()).contains("/change-password");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("mustChangePassword=true, Accept:json 요청: 403 + JSON 바디")
    void doFilter_mustChange_acceptJsonHeader_returns403Json() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/dashboard");
        req.addHeader("Accept", "application/json");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(resp.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
    }

    @Test
    @DisplayName("setup 경로: 패스스루")
    void doFilter_setupPath_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/setup");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }

    @Test
    @DisplayName("login 경로: 패스스루")
    void doFilter_loginPath_passesThrough() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        setAuthentication(principal(true));

        filter.doFilter(req, resp, filterChain);

        verify(filterChain).doFilter(req, resp);
    }
}
