package com.salkcoding.oswl.auth.dto;

import lombok.Data;

/**
 * POST /api/settings/security/mail/test 요청 본문.
 * UI mailForm 필드를 미러링한다.
 */
@Data
public class MailTestRequest {

    private String host;
    private Integer port;
    /** STARTTLS | SSL_TLS | NONE */
    private String encryption;
    private String username;
    private String password;
    private String senderName;
    private String senderAddress;
}
