package com.salkcoding.oswl.auth.dto;

import lombok.Data;

/**
 * PUT /api/settings/security 요청 본문.
 *
 * {@code mailMode}/{@code mail}과 {@code twoFaMode}는 모두 선택 사항이며,
 * UI는 별도 저장 호출로 전송한다:
 *   • saveMail()  → { mailMode, mail: { ... } }
 *   • saveTwoFa() → { twoFaMode }
 */
@Data
public class SecuritySettingUpdateRequest {

    /** MailMode 열거지 이름: DISABLED | SMTP */
    private String mailMode;

    private MailDto mail;

    /** TwoFaMode 열거지 이름: DISABLED | EMAIL_OTP | TOTP */
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
