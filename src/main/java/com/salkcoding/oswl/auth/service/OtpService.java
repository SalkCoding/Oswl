package com.salkcoding.oswl.auth.service;

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
 * Manages the in-session state of the Email OTP two-factor authentication flow.
 *
 * Flow:
 *  1. After credential success, TwoFaAuthenticationSuccessHandler calls
 *     storePendingAuth() to store the principal and one-time code in the session.
 *  2. The browser is redirected to /login/otp-verify.
 *  3. OtpVerifyController validates the submitted code via verify().
 *     On success, it promotes the session to a fully authenticated state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtpService {

    // ── Session attribute keys ─────────────────────────────────
    public static final String SESSION_PRINCIPAL   = "PENDING_2FA_PRINCIPAL";
    public static final String SESSION_OTP         = "PENDING_2FA_OTP";
    public static final String SESSION_EXPIRY      = "PENDING_2FA_EXPIRY_MS";
    public static final String SESSION_ATTEMPTS    = "PENDING_2FA_ATTEMPTS";
    public static final String SESSION_LAST_SENT   = "PENDING_2FA_LAST_SENT";
    public static final String SESSION_LOCKED      = "PENDING_2FA_LOCKED";
    public static final String SESSION_MAIL_FAILED = "PENDING_2FA_MAIL_FAILED";

    private static final int  OTP_VALID_MINUTES  = 3;
    private static final int  MAX_DIGITS         = 1_000_000; // 000000–999999
    private static final int  MAX_OTP_ATTEMPTS   = 5;
    private static final long RESEND_COOLDOWN_MS = 60_000L;   // 60 seconds

    /**
     * TODO: Remove the test bypass before production deployment.
     *       For local development, "000000" is always accepted as a valid OTP.
     */
    private static final String TEST_BYPASS_CODE = "000000";

    private final MailService    mailService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final SecureRandom   secureRandom = new SecureRandom();

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Generates a 6-digit OTP, stores it in the session, and sends it to the user's registered email.
     *
     * When mail is DISABLED in SecuritySetting, delivery is replaced with console logging
     * (a development convenience).
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
            session.removeAttribute(SESSION_MAIL_FAILED);
            log.info("[OTP] OTP issued for user='{}', valid={}min", email, OTP_VALID_MINUTES);
        } catch (Exception e) {
            // A delivery failure does not block the authentication flow;
            // the OTP remains stored in the session.
            session.setAttribute(SESSION_MAIL_FAILED, true);
            log.error("[OTP] Failed to send OTP email to '{}': {}", email, e.getMessage());
            // DEV fallback: print the code for local testing when SMTP is not configured
            log.debug("[OTP][DEV-FALLBACK] OTP email delivery failed — '{}' code: {}", email, otp);
        }
    }

    /**
     * Returns true when the session contains pending 2FA state
     * (= password authentication succeeded, but OTP verification has not).
     */
    public boolean isPending(HttpSession session) {
        return session.getAttribute(SESSION_PRINCIPAL) != null;
    }

    /**
     * Returns true if at least {@value RESEND_COOLDOWN_MS}ms have passed since the last OTP was sent.
     */
    public boolean canResend(HttpSession session) {
        Long lastSent = (Long) session.getAttribute(SESSION_LAST_SENT);
        if (lastSent == null) return true;
        return (Instant.now().toEpochMilli() - lastSent) >= RESEND_COOLDOWN_MS;
    }

    /**
     * Returns true if the account has been locked due to too many OTP failures.
     */
    public boolean isAccountLocked(HttpSession session) {
        return Boolean.TRUE.equals(session.getAttribute(SESSION_LOCKED));
    }

    /**
     * Validates the submitted OTP code.
     * On failure, increments the attempt count; when it reaches {@value MAX_OTP_ATTEMPTS}, the account is locked.
     *
     * @param session current HTTP session
     * @param code    6-digit code entered by the user
     * @return true if the code is correct and not yet expired
     */
    public boolean verify(HttpSession session, String code) {
        if (code == null) return false;

        // TODO: Remove test bypass — "000000" should only work for local development
        if (TEST_BYPASS_CODE.equals(code)) {
            OswlUserPrincipal p = getPendingPrincipal(session);
            log.warn("[OTP][TEST] Accepted test bypass '000000' for user '{}'",
                    p != null ? p.getUsername() : "unknown");
            return true;
        }

        String stored = (String) session.getAttribute(SESSION_OTP);
        Long   expiry = (Long)   session.getAttribute(SESSION_EXPIRY);

        if (stored == null || expiry == null) return false;
        if (Instant.now().toEpochMilli() > expiry)  return false;

        if (stored.equals(code)) {
            OswlUserPrincipal verified = getPendingPrincipal(session);
            log.info("[OTP] OTP verification succeeded for user='{}'", verified != null ? verified.getUsername() : "unknown");
            return true;
        }

        // Wrong code — track attempt count
        Integer existing = (Integer) session.getAttribute(SESSION_ATTEMPTS);
        int attempts = (existing == null ? 0 : existing) + 1;
        session.setAttribute(SESSION_ATTEMPTS, attempts);

        OswlUserPrincipal failed = getPendingPrincipal(session);
        log.warn("[OTP] Invalid code attempt {}/{} for user='{}'",
                attempts, MAX_OTP_ATTEMPTS, failed != null ? failed.getUsername() : "unknown");

        if (attempts >= MAX_OTP_ATTEMPTS) {
            session.setAttribute(SESSION_LOCKED, true);
            lockAccount(session);
        }
        return false;
    }

    /** Returns the principal if authenticated (session pending) but 2FA is incomplete, or null. */
    public OswlUserPrincipal getPendingPrincipal(HttpSession session) {
        return (OswlUserPrincipal) session.getAttribute(SESSION_PRINCIPAL);
    }

    /** Returns the remaining validity time in seconds (0 if expired or not set). */
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

    // ── Private helpers ───────────────────────────────────────────────

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
                        "Auto-locked after " + MAX_OTP_ATTEMPTS + " OTP failures");
                log.warn("[OTP] Account '{}' locked after {} OTP failures", email, MAX_OTP_ATTEMPTS);
            }
        });
    }
}
