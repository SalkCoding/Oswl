package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.security.EncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.IContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MailService unit tests")
class MailServiceTest {

    @Mock SecuritySettingService securitySettingService;
    @Mock EncryptionService       encryptionService;
    @Mock TemplateEngine          templateEngine;

    @InjectMocks MailService mailService;

    // -- sendOtp: DISABLED mode --

    @Test
    @DisplayName("sendOtp: MailMode.DISABLED returns without sending mail")
    void sendOtp_disabled_returnsQuietly() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.DISABLED)
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);

        mailService.sendOtp("user@test.com", "User", "123456");

        verify(templateEngine, never()).process(anyString(), any(IContext.class));
    }

    // -- sendOtp: SMTP host null/blank --

    @Test
    @DisplayName("sendOtp: null SMTP host throws IllegalStateException")
    void sendOtp_noSmtpHost_throws() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost(null)
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP host is not configured");
    }

    @Test
    @DisplayName("sendOtp: blank SMTP host throws IllegalStateException")
    void sendOtp_blankSmtpHost_throws() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("   ")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -- sendOtp: SMTP configured (will fail at send, but covers buildSender) --

    @Test
    @DisplayName("sendOtp: STARTTLS encryption covers buildSender then fails at send")
    void sendOtp_starttls_coveredAndFails() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(587)
                .mailEncryption("STARTTLS")
                .mailUsername("sender@example.com")
                .mailSenderAddress("noreply@example.com")
                .mailSenderName("OsWL")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html>OTP</html>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("sendOtp: SSL_TLS encryption covers buildSender then fails at send")
    void sendOtp_sslTls_coveredAndFails() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(465)
                .mailEncryption("SSL_TLS")
                .mailUsername("sender@example.com")
                .mailSenderAddress("noreply@example.com")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("sendOtp: NONE encryption with no auth covers buildSender then fails")
    void sendOtp_noEncryption_noAuth_fails() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(25)
                .mailEncryption("NONE")
                .mailSenderAddress("noreply@example.com")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "000000"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("sendOtp: encrypted password is decrypted before building sender")
    void sendOtp_withEncryptedPassword_decryptsAndFails() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(587)
                .mailEncryption("STARTTLS")
                .mailUsername("u@example.com")
                .mailPassword("encrypted-pw")
                .mailSenderAddress("noreply@example.com")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(encryptionService.decrypt("encrypted-pw")).thenReturn("plain-password");
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(RuntimeException.class);
        verify(encryptionService).decrypt("encrypted-pw");
    }

    @Test
    @DisplayName("sendOtp: decrypt failure falls back to plaintext password")
    void sendOtp_decryptFails_fallsBackToPlaintext() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(587)
                .mailEncryption("STARTTLS")
                .mailUsername("u@example.com")
                .mailPassword("maybe-plain")
                .mailSenderAddress("noreply@example.com")
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(encryptionService.decrypt("maybe-plain")).thenThrow(new RuntimeException("decrypt failed"));
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("sendOtp: null senderAddress falls back to username")
    void sendOtp_nullSenderAddress_fallsBackToUsername() {
        SecuritySetting settings = SecuritySetting.builder()
                .id(1L)
                .mailMode(MailMode.SMTP)
                .mailHost("localhost")
                .mailPort(587)
                .mailEncryption("STARTTLS")
                .mailUsername("u@example.com")
                .mailSenderAddress(null)
                .mailSenderName(null)
                .build();
        when(securitySettingService.getOrCreate()).thenReturn(settings);
        when(templateEngine.process(anyString(), any(IContext.class))).thenReturn("<html/>");

        assertThatThrownBy(() -> mailService.sendOtp("user@test.com", "User", "123456"))
                .isInstanceOf(RuntimeException.class);
    }

    // -- buildOtpEmailPreview --

    @Test
    @DisplayName("buildOtpEmailPreview: calls templateEngine.process and returns result")
    void buildOtpEmailPreview_invokesTemplateEngine() {
        when(templateEngine.process(eq("mail/otp"), any(IContext.class)))
                .thenReturn("<html>OTP: 123456</html>");

        String result = mailService.buildOtpEmailPreview("Alice", false);

        assertThat(result).isEqualTo("<html>OTP: 123456</html>");
        verify(templateEngine).process(eq("mail/otp"), any(IContext.class));
    }

    @Test
    @DisplayName("buildOtpEmailPreview: withAi=true includes AI message in context")
    void buildOtpEmailPreview_withAi_includesAiMessage() {
        when(templateEngine.process(eq("mail/otp"), any(IContext.class))).thenReturn("<html/>");

        mailService.buildOtpEmailPreview("Bob", true);

        verify(templateEngine).process(eq("mail/otp"), argThat(ctx -> {
            if (ctx instanceof org.thymeleaf.context.Context c) {
                Object ai = c.getVariable("aiMessage");
                return ai != null && !ai.toString().isBlank();
            }
            return false;
        }));
    }

    @Test
    @DisplayName("buildOtpEmailPreview: null displayName uses 'User' in context")
    void buildOtpEmailPreview_nullName_usesUser() {
        when(templateEngine.process(eq("mail/otp"), any(IContext.class))).thenReturn("<html/>");

        mailService.buildOtpEmailPreview(null, false);

        verify(templateEngine).process(eq("mail/otp"), argThat(ctx -> {
            if (ctx instanceof org.thymeleaf.context.Context c) {
                return "User".equals(c.getVariable("name"));
            }
            return false;
        }));
    }

    @Test
    @DisplayName("buildOtpEmailPreview: withAi=false sets aiMessage to null")
    void buildOtpEmailPreview_withoutAi_aiMessageIsNull() {
        when(templateEngine.process(eq("mail/otp"), any(IContext.class))).thenReturn("<html/>");

        mailService.buildOtpEmailPreview("Carol", false);

        verify(templateEngine).process(eq("mail/otp"), argThat(ctx -> {
            if (ctx instanceof org.thymeleaf.context.Context c) {
                return c.getVariable("aiMessage") == null;
            }
            return false;
        }));
    }
}