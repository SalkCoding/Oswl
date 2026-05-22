package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Records an audit log on successful logout and redirects to /login?logout.
 * Injected into SecurityConfig via .logoutSuccessHandler().
 */
@Component
@RequiredArgsConstructor
public class AuditLogoutSuccessHandler implements LogoutSuccessHandler {

    private final AuditLogService auditLogService;

    @Override
    public void onLogoutSuccess(HttpServletRequest request,
                                HttpServletResponse response,
                                Authentication authentication) throws IOException {
        if (authentication != null) {
            auditLogService.log("AUTH.LOGOUT", "AUTH", null, null, null);
        }
        response.sendRedirect("/login?logout");
    }
}
