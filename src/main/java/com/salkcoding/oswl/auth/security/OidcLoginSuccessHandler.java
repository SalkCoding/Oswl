package com.salkcoding.oswl.auth.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.io.IOException;

/**
 * OIDC SSO success handler. After the IdP (Okta / Entra / any OIDC provider)
 * authenticates the user, this maps the verified email claim onto the existing OsWL user and
 * replaces the security context with the standard {@link OswlUserPrincipal} — so every existing
 * authorization rule (roles, permissions, single-session) behaves exactly as with form login.
 *
 * SSO is provisioning-gated: only users that already exist and are enabled in OsWL may sign in.
 * Because the IdP already performed authentication (and typically MFA), the email OTP step is
 * intentionally skipped for SSO logins.
 */
@Slf4j
@RequiredArgsConstructor
public class OidcLoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Override
    public void onAuthenticationSuccess(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                        @NonNull Authentication authentication) throws IOException {
        String email = extractEmail(authentication);
        if (email == null) {
            log.warn("[OIDC] Login token has no email claim — rejecting.");
            response.sendRedirect("/login?error=sso_no_email");
            return;
        }
        try {
            var principal = userDetailsService.loadUserByUsername(email);
            UsernamePasswordAuthenticationToken oswlAuth =
                    new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(oswlAuth);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            log.info("[OIDC] SSO login mapped to OsWL user '{}'", email);
            response.sendRedirect("/projects");
        } catch (UsernameNotFoundException e) {
            log.warn("[OIDC] SSO email '{}' is not a provisioned OsWL user — rejecting.", email);
            response.sendRedirect("/login?error=sso_unprovisioned");
        }
    }

    private String extractEmail(Authentication authentication) {
        if (authentication.getPrincipal() instanceof OidcUser oidc) {
            if (oidc.getEmail() != null) return oidc.getEmail();
            Object claim = oidc.getAttributes().get("email");
            if (claim instanceof String s && !s.isBlank()) return s;
            Object preferred = oidc.getAttributes().get("preferred_username");
            if (preferred instanceof String s && s.contains("@")) return s;
        }
        return null;
    }
}
