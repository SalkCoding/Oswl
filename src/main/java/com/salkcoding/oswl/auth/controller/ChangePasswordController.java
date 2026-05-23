package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.ChangePasswordService;
import com.salkcoding.oswl.auth.service.SecuritySettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * POST /api/change-password — handles forced password changes for admin-invited users.
 *
 * <p>Security properties:
 * <ul>
 *   <li>CSRF protection via Spring Security's {@code CookieCsrfTokenRepository} (X-XSRF-TOKEN header).</li>
 *   <li>Requires full authentication after OTP. {@link com.salkcoding.oswl.auth.security.MustChangePasswordFilter}
 *       blocks access to other URLs until it succeeds.</li>
 *   <li>Validates the current password before writing.</li>
 *   <li>Rotates the session ID after success to protect against session fixation.</li>
 *   <li>Updates the SecurityContext so the filter flag is cleared immediately without re-login.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChangePasswordController {

    private final ChangePasswordService  changePasswordService;
    private final UserDetailsService     userDetailsService;
    private final SecuritySettingService securitySettingService;

    @PostMapping("/api/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "You are not authenticated."));
        }

        // ── Input validation ───────────────────────────────────────────────

        String currentPw = req.getCurrentPassword() != null ? req.getCurrentPassword() : "";
        String newPw     = req.getNewPassword()     != null ? req.getNewPassword()     : "";
        String confirmPw = req.getConfirmPassword() != null ? req.getConfirmPassword() : "";

        int minLen = securitySettingService.getOrCreate().getMinPasswordLength();
        if (newPw.length() < minLen) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "The new password must be at least " + minLen + " characters long."));
        }
        if (!newPw.equals(confirmPw)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Passwords do not match."));
        }

        // ── Business logic in the service (DB write, audit log) ───────────

        try {
            changePasswordService.changePassword(principal.getUserId(), currentPw, newPw);
        } catch (IllegalArgumentException e) {
            return switch (e.getMessage()) {
                case "CURRENT_PASSWORD_WRONG" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "The current password is incorrect."));
                case "SAME_AS_CURRENT" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "The new password must be different from the current password."));
                default -> {
                    log.error("[ChangePassword] Unexpected validation error for user {}: ",
                            principal.getUsername(), e.getMessage());
                    yield ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
                }
            };
        } catch (Exception e) {
            log.error("[ChangePassword] Error for user {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
        }

        // ── Rebuild principal and update SecurityContext ──────────────────
        // Reload from the DB so mustChangePassword == false is reflected immediately without re-login.

        OswlUserPrincipal updated =
                (OswlUserPrincipal) userDetailsService.loadUserByUsername(principal.getUsername());

        UsernamePasswordAuthenticationToken newAuth =
                new UsernamePasswordAuthenticationToken(updated, null, updated.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(newAuth);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }

        // Rotate the session ID after credential changes to prevent session fixation.
        request.changeSessionId();

        log.info("[ChangePassword] Password successfully changed for user {}.", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/projects"));
    }
}
