package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.WebhookDeliveryStatus;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * One webhook delivery attempt shown in the settings UI.
 */
@Value
@Builder
public class WebhookDeliveryDto {

    Long id;
    WebhookEventType eventType;
    Long projectId;
    String projectName;
    String referenceId;
    WebhookDeliveryStatus status;
    Integer httpStatus;
    String errorMessage;
    int retryCount;
    String payloadSummary;
    LocalDateTime createdAt;
}
