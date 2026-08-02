package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.WebhookProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Single-row Slack/Teams incoming webhook settings.
 * The webhook URL is stored AES-256-GCM encrypted by the service layer.
 */
@Entity
@Table(name = "webhook_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class WebhookSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookProvider provider;

    /** Encrypted incoming webhook URL. */
    @Column(name = "webhook_url", length = 1000)
    private String webhookUrl;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    @Column(name = "notify_new_cve", nullable = false)
    @Builder.Default
    private boolean notifyNewCve = true;

    @Column(name = "notify_gate_failure", nullable = false)
    @Builder.Default
    private boolean notifyGateFailure = true;

    @Column(name = "notify_scan_failure", nullable = false)
    @Builder.Default
    private boolean notifyScanFailure = true;

    @Column(name = "notify_waiver_expiry", nullable = false)
    @Builder.Default
    private boolean notifyWaiverExpiry = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void update(WebhookProvider provider, String webhookUrl,
                       boolean enabled, boolean notifyNewCve,
                       boolean notifyGateFailure, boolean notifyScanFailure,
                       boolean notifyWaiverExpiry) {
        this.provider = provider;
        if (webhookUrl != null) {
            this.webhookUrl = webhookUrl;
        }
        this.enabled = enabled;
        this.notifyNewCve = notifyNewCve;
        this.notifyGateFailure = notifyGateFailure;
        this.notifyScanFailure = notifyScanFailure;
        this.notifyWaiverExpiry = notifyWaiverExpiry;
    }

    public boolean isConfigured() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }
}
