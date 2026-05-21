package com.salkcoding.oswl.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * GET /api/settings/security 응답.
 * mailPassword는 의도적으로 제외한다.
 */
@Data
@Builder
@AllArgsConstructor
public class SecuritySettingResponse {

    private String mailMode;
    private MailDto mail;
    private String twoFaMode;
    private int minPasswordLength;

    @Data
    @Builder
    @AllArgsConstructor
    public static class MailDto {
        private String host;
        private Integer port;
        private String encryption;
        private String username;
        private String senderName;
        private String senderAddress;
    }
}
