package com.salkcoding.oswl.auth.entity;

import com.salkcoding.oswl.auth.enums.MailMode;
import com.salkcoding.oswl.auth.enums.TwoFaMode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Singleton security settings row (id = 1).
 * Stores mail server (SMTP) configuration and 2FA mode for the OsWL instance.
 *
 * ⚠️ mailPassword is stored as-is; consider encrypting in production.
 */
@Entity
@Table(name = "security_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecuritySetting {

    /** Fixed singleton ID — always 1. */
    @Id
    private Long id;

    // ── Mail Server ──────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "mail_mode", nullable = false, length = 20)
    @Builder.Default
    private MailMode mailMode = MailMode.DISABLED;

    @Column(name = "mail_host", length = 255)
    private String mailHost;

    @Column(name = "mail_port")
    private Integer mailPort;

    /** STARTTLS | SSL_TLS | NONE */
    @Column(name = "mail_encryption", length = 20)
    private String mailEncryption;

    @Column(name = "mail_username", length = 255)
    private String mailUsername;

    /** Stored AES-256-GCM encrypted. Never returned to clients. Column length 1000 for ciphertext. */
    @Column(name = "mail_password", length = 1000)
    private String mailPassword;

    @Column(name = "mail_sender_name", length = 100)
    private String mailSenderName;

    @Column(name = "mail_sender_address", length = 255)
    private String mailSenderAddress;

    // ── Two-Factor Auth ──────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "two_fa_mode", nullable = false, length = 20)
    @Builder.Default
    private TwoFaMode twoFaMode = TwoFaMode.DISABLED;

    // ── Password Policy ──────────────────────────────────────────────────

    /** Minimum password length enforced on invite and password change. Defaults to 8. */
    @Column(name = "min_password_length", nullable = false)
    @Builder.Default
    private int minPasswordLength = 8;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
