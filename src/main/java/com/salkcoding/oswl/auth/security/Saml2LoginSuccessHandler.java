package com.salkcoding.oswl.auth.security;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * SAML 2.0 SSO success handler. After the IdP authenticates the user, this maps the
 * verified NameID/email onto an OsWL user and replaces the security context with the
 * standard {@link OswlUserPrincipal}. If no matching account exists yet, a disabled-by-default
 * local user is provisioned so SCIM can then activate and assign roles.
 *
 * Because the IdP already performed authentication (and typically MFA), the email OTP step
 * is intentionally skipped for SSO logins.
 */
@Slf4j
@RequiredArgsConstructor
public class Saml2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @Override
    @Transactional
    public void onAuthenticationSuccess(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                        @NonNull Authentication authentication) throws IOException {
        String email = extractEmail(authentication);
        if (email == null) {
            log.warn("[SAML] Assertion has no usable email/NameID claim — rejecting.");
            response.sendRedirect("/login?error=sso_no_email");
            return;
        }

        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            user = provisionUser(email);
            log.info("[SAML] Provisioned new user '{}' from SAML assertion", email);
            auditLogService.log("USER.CREATE", "USER", user.getId().toString(), user.getEmail(),
                    "source=SAML auto-provisioned");
        }

        if (!user.isEnabled()) {
            log.warn("[SAML] SSO email '{}' maps to a disabled OsWL user — rejecting.", email);
            auditLogService.logAnonymous(email, "SAML.LOGIN_FAILURE", "USER",
                    user.getId().toString(), email, "Account disabled");
            response.sendRedirect("/login?error=sso_disabled");
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

            log.info("[SAML] SSO login mapped to OsWL user '{}'", email);
            auditLogService.log("SAML.LOGIN_SUCCESS", "USER", user.getId().toString(), user.getEmail(), null);
            response.sendRedirect("/projects");
        } catch (UsernameNotFoundException e) {
            log.warn("[SAML] SSO email '{}' could not be loaded as an OsWL user — rejecting.", email);
            response.sendRedirect("/login?error=sso_unprovisioned");
        }
    }

    private User provisionUser(String email) {
        String displayName = email.substring(0, email.indexOf('@'));
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(generateRandomPassword()))
                .displayName(displayName)
                .isSystemAdmin(false)
                .enabled(false)
                .mustChangePassword(false)
                .build();
        return userRepository.save(user);
    }

    private String extractEmail(Authentication authentication) {
        if (authentication.getPrincipal() instanceof Saml2AuthenticatedPrincipal saml) {
            String nameId = saml.getName();
            if (nameId != null && nameId.contains("@")) {
                return nameId.toLowerCase();
            }
            String email = firstStringAttribute(saml, "email");
            if (email != null) {
                return email.toLowerCase();
            }
            String mail = firstStringAttribute(saml, "mail");
            if (mail != null) {
                return mail.toLowerCase();
            }
            String upn = firstStringAttribute(saml, "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn");
            if (upn != null) {
                return upn.toLowerCase();
            }
        }
        return null;
    }

    private String firstStringAttribute(Saml2AuthenticatedPrincipal principal, String name) {
        List<Object> values = principal.getAttribute(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object first = values.get(0);
        if (first instanceof String s && s.contains("@")) {
            return s;
        }
        return null;
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
