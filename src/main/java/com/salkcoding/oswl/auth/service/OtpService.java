package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.User;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Manages the in-session state for the Email OTP two-factor authentication flow.
 *
 * Flow:
 *  1. After credentials are verified, TwoFaAuthenticationSuccessHandler calls
 *     storePendingAuth() to store the principal + one-time code in the session.
 *  2. The browser is redirected to /login/otp-verify.
 *  3. OtpVerifyController validates the submitted code via verify().
 *     On success it promotes the session to fully-authenticated.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    // ── Session attribute keys ────────────────────────────────────────────
    public static final String SESSION_PRINCIPAL  = "PENDING_2FA_PRINCIPAL";
    public static final String SESSION_OTP        = "PENDING_2FA_OTP";
    public static final String SESSION_EXPIRY     = "PENDING_2FA_EXPIRY_MS";
    public static final String SESSION_ATTEMPTS   = "PENDING_2FA_ATTEMPTS";
    public static final String SESSION_LAST_SENT  = "PENDING_2FA_LAST_SENT";
    public static final String SESSION_LOCKED     = "PENDING_2FA_LOCKED";

    private static final int  OTP_VALID_MINUTES  = 3;
    private static final int  MAX_DIGITS         = 1_000_000; // 000000–999999
    private static final int  MAX_OTP_ATTEMPTS   = 5;
    private static final long RESEND_COOLDOWN_MS = 60_000L;   // 60 seconds

    /**
     * TODO: Remove test bypass before going to production.
     *       "000000" is always accepted as a valid OTP for local development.
     */
    private static final String TEST_BYPASS_CODE = "000000";

    private final MailService    mailService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final SecureRandom   secureRandom = new SecureRandom();

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Generates a 6-digit OTP, stores it (along with the principal and expiry)
     * in the given session, then sends it to the user's registered email address.
     *
     * Falls back to console logging when mail is DISABLED in SecuritySetting
     * (development convenience).
     */
    public void storePendingAuth(HttpSession session, OswlUserPrincipal principal) {
        String otp     = String.format("%06d", secureRandom.nextInt(MAX_DIGITS));
        long   expiry  = Instant.now().plusSeconds(60L * OTP_VALID_MINUTES).toEpochMilli();

        session.setAttribute(SESSION_PRINCIPAL, principal);
        session.setAttribute(SESSION_OTP,       otp);
        session.setAttribute(SESSION_EXPIRY,    expiry);
        session.setAttribute(SESSION_LAST_SENT, Instant.now().toEpochMilli());

        String email = principal.getUsername(); // username == email
        String name  = principal.getDisplayName();
        try {
            mailService.sendOtp(email, name, otp);
        } catch (Exception e) {
            // Delivery failure must not block the authentication flow;
            // the OTP is still stored in the session.
            // TODO: surface delivery failure to the user on the OTP page.
            log.error("[OTP] Email delivery failed for '{}': {}", email, e.getMessage());
            // DEV FALLBACK: print code so local testing still works even if SMTP is unconfigured
            log.debug("[OTP][DEV-FALLBACK] Could not email OTP — code for '{}' : {}", email, otp);
        }
    }

    /**
     * Returns true if a pending 2FA entry exists in the session (i.e. the user
     * passed password auth but has not yet completed OTP verification).
     */
    public boolean isPending(HttpSession session) {
        return session.getAttribute(SESSION_PRINCIPAL) != null;
    }

    /**
     * Returns true if at least {@value RESEND_COOLDOWN_MS} ms have elapsed
     * since the last OTP was sent.
     */
    public boolean canResend(HttpSession session) {
        Long lastSent = (Long) session.getAttribute(SESSION_LAST_SENT);
        if (lastSent == null) return true;
        return (Instant.now().toEpochMilli() - lastSent) >= RESEND_COOLDOWN_MS;
    }

    /**
     * Returns true when the account has been locked due to too many failed attempts.
     */
    public boolean isAccountLocked(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(SESSION_LOCKED));
    }

    /**
     * Validates the submitted OTP code.
     * Increments the attempt counter on failure; locks the account at {@value MAX_OTP_ATTEMPTS}.
     *
     * @param session the current HTTP session
     * @param code    the 6-digit code entered by the user
     * @return true if the code is correct AND not yet expired
     */
    public boolean verify(HttpSession session, String code) {
        if (code == null) return false;

        // TODO: Remove test bypass — "000000" always passes for local development only
        if (TEST_BYPASS_CODE.equals(code)) {
            OswlUserPrincipal p = getPendingPrincipal(session);
            log.warn("[OTP][TEST-ONLY] Test bypass '000000' accepted for user '{}'",
                    p != null ? p.getUsername() : "unknown");
            return true;
        }

        String stored = (String) session.getAttribute(SESSION_OTP);
        Long   expiry = (Long)   session.getAttribute(SESSION_EXPIRY);

        if (stored == null || expiry == null) return false;
        if (Instant.now().toEpochMilli() > expiry)  return false;

        if (stored.equals(code)) {
            return true;
        }

        // Wrong code — track attempt count
        Integer existing = (Integer) session.getAttribute(SESSION_ATTEMPTS);
        int attempts = (existing == null ? 0 : existing) + 1;
        session.setAttribute(SESSION_ATTEMPTS, attempts);

        if (attempts >= MAX_OTP_ATTEMPTS) {
            session.setAttribute(SESSION_LOCKED, true);
            lockAccount(session);
        }
        return false;
    }

    /** Returns the authenticated-but-not-yet-2FA-verified principal, or null. */
    public OswlUserPrincipal getPendingPrincipal(HttpSession session) {
        return (OswlUserPrincipal) session.getAttribute(SESSION_PRINCIPAL);
    }

    /** Returns remaining validity in seconds (0 if expired or not set). */
    public long remainingSeconds(HttpSession session) {
        Long expiry = (Long) session.getAttribute(SESSION_EXPIRY);
        if (expiry == null) return 0;
        return Math.max(0, (expiry - Instant.now().toEpochMilli()) / 1000);
    }

    /** Removes all pending 2FA attributes from the session. */
    public void clearPending(HttpSession session) {
        session.removeAttribute(SESSION_PRINCIPAL);
        session.removeAttribute(SESSION_OTP);
        session.removeAttribute(SESSION_EXPIRY);
        session.removeAttribute(SESSION_ATTEMPTS);
        session.removeAttribute(SESSION_LAST_SENT);
        session.removeAttribute(SESSION_LOCKED);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    @Transactional
    private void lockAccount(HttpSession session) {
        OswlUserPrincipal principal = getPendingPrincipal(session);
        if (principal == null) return;

        String email = principal.getUsername();
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.isEnabled()) {
                user.setEnabled(false);
                auditLogService.logAnonymous(email, "USER.DEACTIVATE", "USER",
                        user.getId().toString(), email,
                        "Auto-locked: exceeded " + MAX_OTP_ATTEMPTS + " OTP attempts");
                log.warn("[OTP] Account '{}' locked after {} failed OTP attempts", email, MAX_OTP_ATTEMPTS);
            }
        });
    }
}
