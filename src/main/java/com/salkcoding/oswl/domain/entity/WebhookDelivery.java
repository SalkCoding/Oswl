package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.WebhookDeliveryStatus;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * One recorded webhook delivery attempt. Retries create separate rows so the
 * full history is visible in the settings UI.
 */
@Entity
@Table(name = "webhook_deliveries",
        indexes = {
                @Index(name = "idx_webhook_deliveries_created_at", columnList = "created_at DESC"),
                @Index(name = "idx_webhook_deliveries_status", columnList = "status"),
                @Index(name = "idx_webhook_deliveries_event_type", columnList = "event_type")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class WebhookDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private WebhookEventType eventType;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "project_name", length = 160)
    private String projectName;

    /** Optional scan/component/gate identifier for context. */
    @Column(name = "reference_id", length = 40)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookDeliveryStatus status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "payload_summary", length = 500)
    private String payloadSummary;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
