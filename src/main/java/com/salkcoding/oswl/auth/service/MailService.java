package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.security.EncryptionService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * Builds a dynamic JavaMailSender from the saved SecuritySetting and sends
 * transactional emails (OTP codes, notifications).
 *
 * The mail password stored in SecuritySetting is AES-256-GCM encrypted;
 * this service decrypts it before use and never exposes it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final SecuritySettingService securitySettingService;
    private final EncryptionService encryptionService;

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Sends a 6-digit OTP email to the given address.
     *
     * @param toAddress   recipient email
     * @param displayName recipient's display name (used in greeting)
     * @param otp         the 6-digit OTP code
     * @throws RuntimeException if mail is DISABLED or SMTP delivery fails
     */
    public void sendOtp(String toAddress, String displayName, String otp) {
        SecuritySetting settings = securitySettingService.getOrCreate();

        if (settings.getMailMode() == MailMode.DISABLED) {
            log.warn("[Mail] Mail is DISABLED in SecuritySetting — OTP not sent to '{}'. " +
                     "Enable SMTP under Settings → Security to deliver real OTP codes.", toAddress);
            return;
        }

        if (settings.getMailHost() == null || settings.getMailHost().isBlank()) {
            log.error("[Mail] SMTP host is not configured — cannot send OTP to '{}'.", toAddress);
            throw new IllegalStateException("SMTP host is not configured. Configure it under Settings → Security.");
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
            helper.setText(buildOtpHtml(displayName, otp), true);

            sender.send(message);
            log.info("[Mail] OTP sent to '{}'", toAddress);
        } catch (MessagingException e) {
            log.error("[Mail] Failed to send OTP to '{}': {}", toAddress, e.getMessage());
            throw new RuntimeException("Failed to send verification email: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Mail] Unexpected error sending OTP to '{}': {}", toAddress, e.getMessage());
            throw new RuntimeException("Failed to send verification email", e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(SecuritySetting s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getMailHost());
        sender.setPort(s.getMailPort() != null ? s.getMailPort() : 587);

        if (s.getMailUsername() != null) sender.setUsername(s.getMailUsername());

        if (s.getMailPassword() != null && !s.getMailPassword().isBlank()) {
            try {
                sender.setPassword(encryptionService.decrypt(s.getMailPassword()));
            } catch (Exception e) {
                log.warn("[Mail] Failed to decrypt mail password; trying as-is (legacy plaintext).");
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

    private String buildOtpHtml(String displayName, String otp) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : "User";
        return """
               <!DOCTYPE html>
               <html lang="en">
               <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head>
               <body style="margin:0;padding:0;background:#f4f6f9;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;">
                 <table width="100%%" cellpadding="0" cellspacing="0" style="padding:40px 20px;">
                   <tr><td align="center">
                     <table width="480" cellpadding="0" cellspacing="0"
                            style="background:#ffffff;border-radius:12px;border:1px solid #e5e7eb;overflow:hidden;">
                       <tr>
                         <td style="background:#0f172a;padding:28px 32px;">
                           <span style="color:#ffffff;font-size:20px;font-weight:700;letter-spacing:-0.3px;">OsWL</span>
                         </td>
                       </tr>
                       <tr>
                         <td style="padding:32px;">
                           <p style="margin:0 0 8px;font-size:22px;font-weight:700;color:#0f172a;letter-spacing:-0.3px;">
                             Verification code
                           </p>
                           <p style="margin:0 0 24px;font-size:14px;color:#6b7280;line-height:1.6;">
                             Hi %s, use the code below to complete your login.
                             It expires in <strong>5 minutes</strong>.
                           </p>
                           <div style="background:#f8fafc;border:1px solid #e5e7eb;border-radius:10px;
                                       padding:20px;text-align:center;margin-bottom:24px;">
                             <span style="font-size:36px;font-weight:700;letter-spacing:10px;color:#0f172a;">%s</span>
                           </div>
                           <p style="margin:0;font-size:13px;color:#9ca3af;line-height:1.5;">
                             If you did not request this code, you can safely ignore this email.<br>
                             Do not share this code with anyone.
                           </p>
                         </td>
                       </tr>
                       <tr>
                         <td style="padding:16px 32px;background:#f8fafc;border-top:1px solid #e5e7eb;">
                           <p style="margin:0;font-size:12px;color:#9ca3af;">
                             Sent by OsWL &mdash; Open-source Weakness & License scanner
                           </p>
                         </td>
                       </tr>
                     </table>
                   </td></tr>
                 </table>
               </body>
               </html>
               """.formatted(name, otp);
    }
}
