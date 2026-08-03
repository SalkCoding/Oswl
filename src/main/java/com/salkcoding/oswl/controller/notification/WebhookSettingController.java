package com.salkcoding.oswl.controller.notification;

import com.salkcoding.oswl.controller.spec.WebhookSettingControllerSpec;
import com.salkcoding.oswl.domain.entity.notification.WebhookDelivery;
import com.salkcoding.oswl.domain.entity.notification.WebhookSetting;
import com.salkcoding.oswl.domain.enums.WebhookDeliveryStatus;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import com.salkcoding.oswl.dto.api.WebhookDeliveryDto;
import com.salkcoding.oswl.dto.api.WebhookSettingResponse;
import com.salkcoding.oswl.dto.api.WebhookSettingUpdateRequest;
import com.salkcoding.oswl.dto.api.WebhookTestRequest;
import com.salkcoding.oswl.repository.notification.WebhookDeliveryRepository;
import com.salkcoding.oswl.service.notification.WebhookSettingService;
import com.salkcoding.oswl.service.notification.WebhookClient;
import com.salkcoding.oswl.service.notification.WebhookMessageBuilder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/webhooks")
@PreAuthorize("hasPermission(null, 'SETTINGS_WEBHOOK_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class WebhookSettingController implements WebhookSettingControllerSpec {

    private final WebhookSettingService webhookSettingService;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final WebhookMessageBuilder messageBuilder;
    private final WebhookClient webhookClient;

    @GetMapping
    public ResponseEntity<WebhookSettingResponse> getSettings() {
        WebhookSetting setting = webhookSettingService.getSetting();
        return ResponseEntity.ok(WebhookSettingResponse.builder()
                .provider(setting.getProvider())
                .hasUrl(setting.getWebhookUrl() != null && !setting.getWebhookUrl().isBlank())
                .enabled(setting.isEnabled())
                .notifyNewCve(setting.isNotifyNewCve())
                .notifyGateFailure(setting.isNotifyGateFailure())
                .notifyScanFailure(setting.isNotifyScanFailure())
                .notifyWaiverExpiry(setting.isNotifyWaiverExpiry())
                .build());
    }

    @PutMapping
    public ResponseEntity<Void> saveSettings(@Valid @RequestBody WebhookSettingUpdateRequest request) {
        webhookSettingService.save(request.getProvider(), request.getUrl(), request.isEnabled(),
                request.isNotifyNewCve(), request.isNotifyGateFailure(),
                request.isNotifyScanFailure(), request.isNotifyWaiverExpiry());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testWebhook(@Valid @RequestBody WebhookTestRequest request) {
        WebhookSetting setting = webhookSettingService.getSetting();
        String payload = messageBuilder.buildPayload(setting.getProvider(),
                "OsWL webhook test",
                List.of("This is a test message from OsWL webhook settings."),
                null);

        WebhookClient.DeliveryOutcome outcome = webhookClient.send(
                request.getUrl().strip(), payload, WebhookEventType.NEW_CVE, null, null, "test");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", outcome.success());
        body.put("status", outcome.lastHttpStatus());
        if (outcome.lastError() != null) {
            body.put("error", outcome.lastError());
        }
        return ResponseEntity.ok(body);
    }

    @GetMapping("/deliveries")
    public ResponseEntity<List<WebhookDeliveryDto>> getDeliveries(
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 200);
        List<WebhookDelivery> deliveries;
        if (status != null && !status.isBlank()) {
            try {
                WebhookDeliveryStatus s = WebhookDeliveryStatus.valueOf(status.toUpperCase());
                deliveries = webhookDeliveryRepository.findByStatusOrderByCreatedAtDesc(s,
                        PageRequest.of(0, pageSize));
            } catch (IllegalArgumentException e) {
                deliveries = webhookDeliveryRepository.findAll(PageRequest.of(0, pageSize)).getContent();
            }
        } else if (eventType != null && !eventType.isBlank()) {
            try {
                WebhookEventType e = WebhookEventType.valueOf(eventType.toUpperCase());
                deliveries = webhookDeliveryRepository.findByEventTypeOrderByCreatedAtDesc(e,
                        PageRequest.of(0, pageSize));
            } catch (IllegalArgumentException e) {
                deliveries = webhookDeliveryRepository.findAll(PageRequest.of(0, pageSize)).getContent();
            }
        } else {
            deliveries = webhookDeliveryRepository.findAll(PageRequest.of(0, pageSize)).getContent();
        }
        return ResponseEntity.ok(deliveries.stream().map(this::toDto).toList());
    }

    private WebhookDeliveryDto toDto(WebhookDelivery d) {
        return WebhookDeliveryDto.builder()
                .id(d.getId())
                .eventType(d.getEventType())
                .projectId(d.getProjectId())
                .projectName(d.getProjectName())
                .referenceId(d.getReferenceId())
                .status(d.getStatus())
                .httpStatus(d.getHttpStatus())
                .errorMessage(d.getErrorMessage())
                .retryCount(d.getRetryCount())
                .payloadSummary(d.getPayloadSummary())
                .createdAt(d.getCreatedAt())
                .build();
    }
}
