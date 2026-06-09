package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * PUT /api/settings/security request body.
 *
 * Both {@code mailMode}/{@code mail} and {@code twoFaMode} are optional,
 * and the UI sends them in separate save calls:
 *   • saveMail()  → { mailMode, mail: { ... } }
 *   • saveTwoFa() → { twoFaMode }
 */
@Schema(description = "Security settings update request — mail and 2FA fields can be sent independently")
@Data
public class SecuritySettingUpdateRequest {

    @Schema(description = "Mail mode", example = "SMTP", allowableValues = {"DISABLED", "SMTP"})
    /** MailMode enum name: DISABLED | SMTP */
    private String mailMode;

    @Schema(description = "SMTP mail server configuration")
    private MailDto mail;

    @Schema(description = "Two-factor authentication mode", example = "EMAIL_OTP",
            allowableValues = {"DISABLED", "EMAIL_OTP", "TOTP"})
    /** TwoFaMode enum name: DISABLED | EMAIL_OTP | TOTP */
    private String twoFaMode;

    @Schema(description = "SMTP server configuration")
    @Data
    public static class MailDto {
        @Schema(description = "SMTP host", example = "smtp.example.com")
        private String host;
        @Schema(description = "SMTP port", example = "587")
        private Integer port;
        @Schema(description = "Encryption type", example = "STARTTLS", allowableValues = {"STARTTLS", "SSL_TLS", "NONE"})
        /** STARTTLS | SSL_TLS | NONE */
        private String encryption;
        @Schema(description = "SMTP username", example = "no-reply@example.com")
        private String username;
        @Schema(description = "SMTP password (write-only)", example = "s3cr3t")
        private String password;
        @Schema(description = "Sender display name", example = "OSWL Notifications")
        private String senderName;
        @Schema(description = "Sender email address", example = "no-reply@example.com")
        private String senderAddress;
    }
}
