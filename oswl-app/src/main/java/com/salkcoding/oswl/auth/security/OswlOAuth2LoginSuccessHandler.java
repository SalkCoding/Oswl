package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.LoginCompletionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Completes OIDC login using the existing OsWL user principal (skips local email OTP — IdP MFA applies).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "oswl.oauth2", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class OswlOAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final UserDetailsService userDetailsService;
    private final LoginCompletionService loginCompletionService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcUser oidcUser = (OidcUser) authentication.getPrincipal();
        String email = oidcUser.getEmail();
        var user = userRepository.findByEmail(email.strip().toLowerCase())
                .or(() -> userRepository.findByEmail(email.strip()))
                .orElseThrow();
        OswlUserPrincipal principal = (OswlUserPrincipal) userDetailsService.loadUserByUsername(user.getEmail());
        var token = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(token);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                SecurityContextHolder.getContext());
        loginCompletionService.recordSuccessfulLogin(principal.getUsername());
        String dest = principal.isMustChangePassword() ? "/change-password" : "/projects";
        log.info("[Auth] OIDC login succeeded for user='{}' → {}", principal.getUsername(), dest);
        response.sendRedirect(request.getContextPath() + dest);
    }
}
