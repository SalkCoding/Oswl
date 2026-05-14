package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public static final String SESSION_PRINCIPAL = "PENDING_2FA_PRINCIPAL";
    public static final String SESSION_OTP       = "PENDING_2FA_OTP";
    public static final String SESSION_EXPIRY    = "PENDING_2FA_EXPIRY_MS";

    private static final int OTP_VALID_MINUTES = 5;
    private static final int MAX_DIGITS        = 1_000_000; // 000000–999999

    /**
     * TODO: Remove test bypass before going to production.
     *       "000000" is always accepted as a valid OTP for local development.
     */
    private static final String TEST_BYPASS_CODE = "000000";

    private final MailService mailService;
    private final SecureRandom secureRandom = new SecureRandom();

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
            log.warn("[OTP][DEV-FALLBACK] Could not email OTP — code for '{}' : {}", email, otp);
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
     * Validates the submitted OTP code.
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
        return stored.equals(code);
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
    }
}
