package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.TrustedDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.io.IOException;

import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogoutSuccessHandler 단위 테스트")
class AuditLogoutSuccessHandlerTest {

    @Mock AuditLogService auditLogService;
    @Mock TrustedDeviceService trustedDeviceService;
    @InjectMocks AuditLogoutSuccessHandler handler;

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock Authentication authentication;

    @Test
    @DisplayName("onLogoutSuccess: 인증이 있으면 감사 로그를 기록하고 리다이렉트한다")
    void onLogoutSuccess_withAuthentication_logsAndRedirects() throws IOException {
        handler.onLogoutSuccess(request, response, authentication);

        verify(trustedDeviceService).clearTrusted(request, response);
        verify(auditLogService).log("AUTH.LOGOUT", "AUTH", null, null, null);
        verify(response).sendRedirect("/login?logout");
    }

    @Test
    @DisplayName("onLogoutSuccess: 인증이 null이면 감사 로그를 기록하지 않고 리다이렉트한다")
    void onLogoutSuccess_nullAuthentication_skipsLogAndRedirects() throws IOException {
        handler.onLogoutSuccess(request, response, null);

        verify(trustedDeviceService).clearTrusted(request, response);
        verifyNoInteractions(auditLogService);
        verify(response).sendRedirect("/login?logout");
    }
}
