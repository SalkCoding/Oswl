package com.salkcoding.oswl.auth.dto;

import lombok.Data;

/**
 * PUT /api/settings/security request body.
 *
 * Both {@code mailMode}/{@code mail} and {@code twoFaMode} are optional,
 * and the UI sends them in separate save calls:
 *   • saveMail()  → { mailMode, mail: { ... } }
 *   • saveTwoFa() → { twoFaMode }
 */
@Data
public class SecuritySettingUpdateRequest {

    /** MailMode enum name: DISABLED | SMTP */
    private String mailMode;

    private MailDto mail;

    /** TwoFaMode enum name: DISABLED | EMAIL_OTP | TOTP */
    private String twoFaMode;

    @Data
    public static class MailDto {
        private String host;
        private Integer port;
        /** STARTTLS | SSL_TLS | NONE */
        private String encryption;
        private String username;
        private String password;
        private String senderName;
        private String senderAddress;
    }
}
