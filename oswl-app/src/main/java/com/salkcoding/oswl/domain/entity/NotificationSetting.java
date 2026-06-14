package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/** Singleton notification channel configuration (id = 1). */
@Entity
@Table(name = "notification_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationSetting {

    @Id
    private Long id;

    /** AES-encrypted Slack incoming webhook URL. */
    @Column(name = "slack_webhook_url", length = 1000)
    private String slackWebhookUrl;

    /** AES-encrypted Microsoft Teams incoming webhook URL. */
    @Column(name = "teams_webhook_url", length = 1000)
    private String teamsWebhookUrl;

    @Column(name = "email_digest_enabled", nullable = false)
    @Builder.Default
    private boolean emailDigestEnabled = false;

    @Column(name = "notify_critical_cve", nullable = false)
    @Builder.Default
    private boolean notifyCriticalCve = true;

    @Column(name = "notify_license_violation", nullable = false)
    @Builder.Default
    private boolean notifyLicenseViolation = true;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
