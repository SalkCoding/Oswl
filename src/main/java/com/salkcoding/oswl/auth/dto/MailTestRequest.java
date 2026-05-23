package com.salkcoding.oswl.auth.dto;

import lombok.Data;

/**
 * POST /api/settings/security/mail/test request body.
 * Mirrors the UI mailForm fields.
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
