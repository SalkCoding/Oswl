package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.ChangePasswordRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.ChangePasswordService;
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
 * POST /api/change-password — processes the forced password change for admin-invited users.
 *
 * <p>Security properties:
 * <ul>
 *   <li>CSRF-protected via Spring Security's {@code CookieCsrfTokenRepository} (X-XSRF-TOKEN header).</li>
 *   <li>Requires full authentication (past OTP). {@link com.salkcoding.oswl.auth.security.MustChangePasswordFilter}
 *       prevents access to any other URL until this succeeds.</li>
 *   <li>Current password verified before any write.</li>
 *   <li>Session ID rotated after success to prevent session-fixation attacks.</li>
 *   <li>SecurityContext updated so the filter flag clears immediately without a re-login.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChangePasswordController {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final ChangePasswordService changePasswordService;
    private final UserDetailsService    userDetailsService;

    @PostMapping("/api/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @RequestBody ChangePasswordRequest req,
            @AuthenticationPrincipal OswlUserPrincipal principal,
            HttpServletRequest request) {

        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated."));
        }

        // ── Input validation ──────────────────────────────────────────────────

        String currentPw = req.getCurrentPassword() != null ? req.getCurrentPassword() : "";
        String newPw     = req.getNewPassword()     != null ? req.getNewPassword()     : "";
        String confirmPw = req.getConfirmPassword() != null ? req.getConfirmPassword() : "";

        if (newPw.length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "New password must be at least " + MIN_PASSWORD_LENGTH + " characters."));
        }
        if (!newPw.equals(confirmPw)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Passwords do not match."));
        }

        // ── Business logic (DB write, audit log) in service ──────────────────

        try {
            changePasswordService.changePassword(principal.getUserId(), currentPw, newPw);
        } catch (IllegalArgumentException e) {
            return switch (e.getMessage()) {
                case "CURRENT_PASSWORD_WRONG" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "Current password is incorrect."));
                case "SAME_AS_CURRENT" ->
                        ResponseEntity.badRequest()
                                .body(Map.of("message", "New password must be different from the current password."));
                default -> {
                    log.error("[ChangePassword] Unexpected validation error for user {}: {}",
                            principal.getUsername(), e.getMessage());
                    yield ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
                }
            };
        } catch (Exception e) {
            log.error("[ChangePassword] Error for user {}: {}", principal.getUsername(), e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "An unexpected error occurred."));
        }

        // ── Rebuild principal and update SecurityContext ──────────────────────
        // Reload from DB so that mustChangePassword == false is reflected immediately
        // without requiring a re-login.

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

        // Rotate session ID to mitigate session-fixation after credential change.
        request.changeSessionId();

        log.info("[ChangePassword] Password changed successfully for user: {}", principal.getUsername());
        return ResponseEntity.ok(Map.of("redirectUrl", "/projects"));
    }
}
