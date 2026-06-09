package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * GET /api/settings/security response.
 * mailPassword is intentionally excluded.
 */
@Schema(description = "Security settings response — mail password is never returned")
@Data
@Builder
@AllArgsConstructor
public class SecuritySettingResponse {

    @Schema(description = "Mail mode", example = "SMTP", allowableValues = {"DISABLED", "SMTP"})
    private String mailMode;
    @Schema(description = "SMTP mail server configuration")
    private MailDto mail;
    @Schema(description = "Two-factor authentication mode", example = "EMAIL_OTP",
            allowableValues = {"DISABLED", "EMAIL_OTP", "TOTP"})
    private String twoFaMode;
    @Schema(description = "Minimum required password length", example = "8")
    private int minPasswordLength;

    @Schema(description = "SMTP server configuration (password omitted)")
    @Data
    @Builder
    @AllArgsConstructor
    public static class MailDto {
        @Schema(description = "SMTP host", example = "smtp.example.com")
        private String host;
        @Schema(description = "SMTP port", example = "587")
        private Integer port;
        @Schema(description = "Encryption type", example = "STARTTLS", allowableValues = {"STARTTLS", "SSL_TLS", "NONE"})
        private String encryption;
        @Schema(description = "SMTP username", example = "no-reply@example.com")
        private String username;
        @Schema(description = "Sender display name", example = "OSWL Notifications")
        private String senderName;
        @Schema(description = "Sender email address", example = "no-reply@example.com")
        private String senderAddress;
    }
}
