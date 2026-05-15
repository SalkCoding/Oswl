package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.service.UserManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class OswlAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final int WARN_THRESHOLD = 5;
    private static final int LOCK_THRESHOLD = 10;

    private final UserManagementService userManagementService;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        if (exception instanceof DisabledException) {
            response.sendRedirect("/login?disabled");
            return;
        }

        String email = request.getParameter("email");
        if (email != null && !email.isBlank()) {
            int count = userManagementService.handleLoginFailure(email.trim().toLowerCase());
            if (count >= LOCK_THRESHOLD) {
                response.sendRedirect("/login?disabled");
                return;
            }
            if (count >= WARN_THRESHOLD) {
                response.sendRedirect("/login?error&warn");
                return;
            }
        }
        response.sendRedirect("/login?error");
    }
}
