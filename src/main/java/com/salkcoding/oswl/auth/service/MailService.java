package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.security.EncryptionService;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.Properties;

/**
 * Creates a dynamic JavaMailSender from the stored SecuritySetting and
 * sends transactional emails (OTP codes, notifications).
 *
 * The mail password stored in SecuritySetting is encrypted with AES-256-GCM;
 * this service decrypts it before use and does not expose it externally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final SecuritySettingService securitySettingService;
    private final EncryptionService encryptionService;
    private final TemplateEngine templateEngine;

    @Value("classpath:static/icon/icon-logo.svg")
    private Resource logoSvgResource;
    private String logoDataUri = "";

    @PostConstruct
    void init() {
        try {
            byte[] bytes = logoSvgResource.getInputStream().readAllBytes();
            logoDataUri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes);
            log.debug("[Mail] Logo SVG loaded successfully ({} bytes)", bytes.length);
        } catch (Exception e) {
            log.warn("[Mail] Failed to load logo SVG — the logo will not be shown in the email header: {}", e.getMessage());
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Sends a 6-digit OTP email to the given address.
     *
     * @param toAddress   Recipient email
     * @param displayName Recipient display name (used in the greeting)
     * @param otp         6-digit OTP code
     * @throws RuntimeException if mail is DISABLED or SMTP sending fails
     */
    public void sendOtp(String toAddress, String displayName, String otp) {
        SecuritySetting settings = securitySettingService.getOrCreate();

        if (settings.getMailMode() == MailMode.DISABLED) {
            log.warn("[Mail] Mail is DISABLED in SecuritySetting — OTP not sent to '{}'. " +
                     "Enable SMTP in Settings → Security.", toAddress);
            return;
        }

        if (settings.getMailHost() == null || settings.getMailHost().isBlank()) {
            log.error("[Mail] SMTP host is not configured — cannot send OTP to '{}'.", toAddress);
            throw new IllegalStateException("SMTP host is not configured. Configure it in Settings → Security.");
        }

        JavaMailSenderImpl sender = buildSender(settings);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            String fromAddress = settings.getMailSenderAddress() != null
                    ? settings.getMailSenderAddress()
                    : settings.getMailUsername();
            String fromName = settings.getMailSenderName() != null
                    ? settings.getMailSenderName()
                    : "OsWL";

            helper.setFrom(fromAddress, fromName);
            helper.setTo(toAddress);
            helper.setSubject("[OsWL] Your verification code: " + otp);
            helper.setText(buildOtpHtml(displayName, otp, null), true);

            sender.send(message);
            log.info("[Mail] OTP sent to '{}'", toAddress);
        } catch (MessagingException e) {
            log.error("[Mail] Failed to send OTP to '{}': {}", toAddress, e.getMessage());
            throw new RuntimeException("Failed to send authentication email: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Mail] Unexpected error while sending OTP to '{}': {}", toAddress, e.getMessage());
            throw new RuntimeException("Failed to send authentication email", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(SecuritySetting s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getMailHost());
        sender.setPort(s.getMailPort() != null ? s.getMailPort() : 587);

        if (s.getMailUsername() != null) sender.setUsername(s.getMailUsername());

        if (s.getMailPassword() != null && !s.getMailPassword().isBlank()) {
            try {
                sender.setPassword(encryptionService.decrypt(s.getMailPassword()));
            } catch (Exception e) {
                log.warn("[Mail] Failed to decrypt mail password; trying plaintext instead (legacy support).");
                sender.setPassword(s.getMailPassword());
            }
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        String enc = s.getMailEncryption();
        if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        } else if ("SSL_TLS".equalsIgnoreCase(enc)) {
            sender.setProtocol("smtps");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            boolean hasAuth = s.getMailUsername() != null && !s.getMailUsername().isBlank();
            props.put("mail.smtp.auth", String.valueOf(hasAuth));
        }

        return sender;
    }

    private String buildOtpHtml(String displayName, String otp, String aiMessage) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : "User";
        Context ctx = new Context();
        ctx.setVariable("name", name);
        ctx.setVariable("otp", otp);
        ctx.setVariable("logoDataUri", logoDataUri);
        ctx.setVariable("aiMessage", aiMessage);
        return templateEngine.process("mail/otp", ctx);
    }

    /** For local development — renders and returns the OTP email template with dummy data. */
    public String buildOtpEmailPreview(String name, boolean withAi) {
        String ai = withAi
                ? "This sign-in request originates from a device and location consistent with your previous sessions."
                  + " No suspicious activity or anomalies were detected."
                  + " If this was not you, please change your password immediately."
                : null;
        return buildOtpHtml(name, "123456", ai);
    }
}
