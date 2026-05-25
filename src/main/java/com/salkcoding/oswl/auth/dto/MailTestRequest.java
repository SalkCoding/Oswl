package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * POST /api/settings/security/mail/test request body.
 * Mirrors the UI mailForm fields.
 */
@Schema(description = "SMTP mail test request — sends a test email using the provided SMTP settings")
@Data
public class MailTestRequest {

    @Schema(description = "SMTP host", example = "smtp.example.com")
    private String host;
    @Schema(description = "SMTP port", example = "587")
    private Integer port;
    @Schema(description = "Encryption type", example = "STARTTLS", allowableValues = {"STARTTLS", "SSL_TLS", "NONE"})
    /** STARTTLS | SSL_TLS | NONE */
    private String encryption;
    @Schema(description = "SMTP username", example = "no-reply@example.com")
    private String username;
    @Schema(description = "SMTP password", example = "s3cr3t")
    private String password;
    @Schema(description = "Sender display name", example = "OSWL Notifications")
    private String senderName;
    @Schema(description = "Sender email address", example = "no-reply@example.com")
    private String senderAddress;
}
