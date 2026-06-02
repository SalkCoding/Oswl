package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.security.OtpPendingIdentity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpService unit tests")
class OtpServiceTest {

    @Mock MailService         mailService;
    @Mock UserRepository      userRepository;
    @Mock AuditLogService     auditLogService;
    @Mock UserDetailsService  userDetailsService;

    @InjectMocks OtpService otpService;

    private OswlUserPrincipal principal;
    private OtpPendingIdentity pendingIdentity;
    private MockHttpSession   session;

    @BeforeEach
    void setUp() {
        principal = new OswlUserPrincipal(
                1L, "user@example.com", "{noop}pass", "Test User",
                false, true, List.of(), Set.of(), Set.of(Permission.SCAN_SUBMIT), false);
        pendingIdentity = OtpPendingIdentity.from(principal);
        session = new MockHttpSession();
        lenient().when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(principal);
    }

    // ── storePendingAuth ─────────────────────────────────────────────

    @Test
    @DisplayName("storePendingAuth stores OTP attributes and sends mail")
    void storePendingAuth_storesAttributesAndSendsMail() throws Exception {
        otpService.storePendingAuth(session, principal);

        assertThat(session.getAttribute(OtpService.SESSION_PRINCIPAL)).isEqualTo(pendingIdentity);
        assertThat(session.getAttribute(OtpService.SESSION_PRINCIPAL)).isNotInstanceOf(OswlUserPrincipal.class);
        assertThat(session.getAttribute(OtpService.SESSION_OTP)).isNotNull();
        assertThat(session.getAttribute(OtpService.SESSION_EXPIRY)).isNotNull();
        verify(mailService).sendOtp(eq("user@example.com"), eq("Test User"), anyString());
    }

    @Test
    @DisplayName("storePendingAuth marks MAIL_FAILED when mail throws")
    void storePendingAuth_mailFailed_setsFlag() throws Exception {
        doThrow(new RuntimeException("SMTP down")).when(mailService).sendOtp(any(), any(), any());

        otpService.storePendingAuth(session, principal);

        assertThat(session.getAttribute(OtpService.SESSION_MAIL_FAILED)).isEqualTo(true);
    }

    // ── isPending ────────────────────────────────────────────────────

    @Test
    @DisplayName("isPending is false for a fresh session")
    void isPending_false_whenNoPrincipal() {
        assertThat(otpService.isPending(session)).isFalse();
    }

    @Test
    @DisplayName("isPending is true after storePendingAuth")
    void isPending_true_afterStore() throws Exception {
        otpService.storePendingAuth(session, principal);
        assertThat(otpService.isPending(session)).isTrue();
    }

    // ── verify ───────────────────────────────────────────────────────

    @Test
    @DisplayName("verify rejects the former test bypass code '000000'")
    void verify_rejectsTestBypassCode() {
        String otp = "123456";
        long expiry = Instant.now().plusSeconds(180).toEpochMilli();
        session.setAttribute(OtpService.SESSION_PRINCIPAL, pendingIdentity);
        session.setAttribute(OtpService.SESSION_OTP, otp);
        session.setAttribute(OtpService.SESSION_EXPIRY, expiry);

        assertThat(otpService.verify(session, "000000")).isFalse();
    }

    @Test
    @DisplayName("verify correct code returns true")
    void verify_correctCode_returnsTrue() {
        String otp = "123456";
        long expiry = Instant.now().plusSeconds(180).toEpochMilli();
        session.setAttribute(OtpService.SESSION_PRINCIPAL, pendingIdentity);
        session.setAttribute(OtpService.SESSION_OTP, otp);
        session.setAttribute(OtpService.SESSION_EXPIRY, expiry);

        assertThat(otpService.verify(session, otp)).isTrue();
    }

    @Test
    @DisplayName("verify expired OTP returns false")
    void verify_expiredOtp_returnsFalse() {
        String otp = "123456";
        long expiry = Instant.now().minusSeconds(1).toEpochMilli(); // already expired
        session.setAttribute(OtpService.SESSION_PRINCIPAL, pendingIdentity);
        session.setAttribute(OtpService.SESSION_OTP, otp);
        session.setAttribute(OtpService.SESSION_EXPIRY, expiry);

        assertThat(otpService.verify(session, otp)).isFalse();
    }

    @Test
    @DisplayName("verify wrong code increments attempt counter")
    void verify_wrongCode_incrementsAttempts() {
        long expiry = Instant.now().plusSeconds(180).toEpochMilli();
        session.setAttribute(OtpService.SESSION_PRINCIPAL, pendingIdentity);
        session.setAttribute(OtpService.SESSION_OTP, "999999");
        session.setAttribute(OtpService.SESSION_EXPIRY, expiry);

        otpService.verify(session, "000001"); // not bypass, not correct

        assertThat(session.getAttribute(OtpService.SESSION_ATTEMPTS)).isEqualTo(1);
    }

    @Test
    @DisplayName("verify locks account after 5 wrong attempts")
    void verify_maxAttempts_locksAccount() {
        long expiry = Instant.now().plusSeconds(180).toEpochMilli();
        session.setAttribute(OtpService.SESSION_PRINCIPAL, pendingIdentity);
        session.setAttribute(OtpService.SESSION_OTP, "999999");
        session.setAttribute(OtpService.SESSION_EXPIRY, expiry);

        com.salkcoding.oswl.auth.entity.User user = mock(com.salkcoding.oswl.auth.entity.User.class);
        when(user.isEnabled()).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) {
            otpService.verify(session, "000001");
        }

        assertThat(session.getAttribute(OtpService.SESSION_LOCKED)).isEqualTo(true);
        verify(user).setEnabled(false);
    }

    // ── canResend ─────────────────────────────────────────────────────

    @Test
    @DisplayName("canResend is true when no lastSent is stored")
    void canResend_true_whenNoLastSent() {
        assertThat(otpService.canResend(session)).isTrue();
    }

    @Test
    @DisplayName("canResend is false within the 60s cooldown")
    void canResend_false_withinCooldown() {
        session.setAttribute(OtpService.SESSION_LAST_SENT, Instant.now().toEpochMilli());
        assertThat(otpService.canResend(session)).isFalse();
    }

    // ── clearPending ──────────────────────────────────────────────────

    @Test
    @DisplayName("clearPending removes all 2FA session attributes")
    void clearPending_removesAllAttributes() throws Exception {
        otpService.storePendingAuth(session, principal);
        otpService.clearPending(session);

        assertThat(session.getAttribute(OtpService.SESSION_PRINCIPAL)).isNull();
        assertThat(session.getAttribute(OtpService.SESSION_OTP)).isNull();
        assertThat(session.getAttribute(OtpService.SESSION_EXPIRY)).isNull();
    }

    // ── isAccountLocked ───────────────────────────────────────────────

    @Test
    @DisplayName("isAccountLocked: returns false when not set")
    void isAccountLocked_notSet_returnsFalse() {
        assertThat(otpService.isAccountLocked(session)).isFalse();
    }

    @Test
    @DisplayName("isAccountLocked: returns true when locked flag is set")
    void isAccountLocked_set_returnsTrue() {
        session.setAttribute(OtpService.SESSION_LOCKED, Boolean.TRUE);
        assertThat(otpService.isAccountLocked(session)).isTrue();
    }

    // ── getPendingPrincipal ────────────────────────────────────────────

    @Test
    @DisplayName("getPendingPrincipal: returns null when not set")
    void getPendingPrincipal_notSet_returnsNull() {
        assertThat(otpService.getPendingPrincipal(session)).isNull();
    }

    @Test
    @DisplayName("getPendingPrincipal: returns stored principal")
    void getPendingPrincipal_set_returnsPrincipal() throws Exception {
        otpService.storePendingAuth(session, principal);
        assertThat(otpService.getPendingPrincipal(session)).isEqualTo(principal);
    }

    // ── remainingSeconds ──────────────────────────────────────────────

    @Test
    @DisplayName("remainingSeconds: returns 0 when expiry not set")
    void remainingSeconds_notSet_returnsZero() {
        assertThat(otpService.remainingSeconds(session)).isEqualTo(0L);
    }

    @Test
    @DisplayName("remainingSeconds: returns positive value when not yet expired")
    void remainingSeconds_notExpired_returnsPositive() throws Exception {
        otpService.storePendingAuth(session, principal);
        assertThat(otpService.remainingSeconds(session)).isGreaterThan(0L);
    }

    @Test
    @DisplayName("remainingSeconds: returns 0 when already expired")
    void remainingSeconds_expired_returnsZero() {
        session.setAttribute(OtpService.SESSION_EXPIRY, Instant.now().toEpochMilli() - 1000L);
        assertThat(otpService.remainingSeconds(session)).isEqualTo(0L);
    }
}

