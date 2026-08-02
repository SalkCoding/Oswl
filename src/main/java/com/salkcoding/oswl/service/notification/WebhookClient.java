package com.salkcoding.oswl.service.notification;

import com.salkcoding.oswl.domain.entity.WebhookDelivery;
import com.salkcoding.oswl.domain.enums.WebhookDeliveryStatus;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import com.salkcoding.oswl.repository.WebhookDeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Sends JSON payloads to an incoming webhook URL with retry.
 * Every attempt is persisted so failures are visible in the UI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookClient {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();

    public record DeliveryOutcome(boolean success, int lastHttpStatus, String lastError) {}

    /**
     * Sends the payload with exponential-backoff retries. A row is written for each
     * attempt so the history shows transient failures as well as the final outcome.
     * Runs in a fresh transaction so delivery history is recorded even when the caller
     * is read-only or rolls back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryOutcome send(String url, String payload, WebhookEventType eventType,
                                Long projectId, String projectName, String referenceId) {
        DeliveryOutcome outcome = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            outcome = trySend(url, payload, eventType, projectId, projectName, referenceId, attempt);
            if (outcome.success) {
                return outcome;
            }
            if (attempt < MAX_RETRIES) {
                long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                log.debug("[Webhook] Retrying {} in {} ms (attempt {} failed)", eventType, backoff, attempt);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return outcome;
                }
            }
        }
        return outcome != null ? outcome : new DeliveryOutcome(false, 0, "No attempts made");
    }

    private DeliveryOutcome trySend(String url, String payload, WebhookEventType eventType,
                                    Long projectId, String projectName, String referenceId,
                                    int attempt) {
        int status = 0;
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            status = response.statusCode();

            if (status >= 200 && status < 300) {
                recordDelivery(eventType, projectId, projectName, referenceId,
                        WebhookDeliveryStatus.SUCCESS, status, null, attempt, payload);
                log.info("[Webhook] {} delivered successfully (status={})", eventType, status);
                return new DeliveryOutcome(true, status, null);
            }
            error = "HTTP " + status;
            if (response.body() != null && !response.body().isBlank()) {
                error += ": " + truncate(response.body(), 200);
            }
        } catch (IllegalArgumentException e) {
            error = "Invalid URL: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            error = "Request timed out";
        } catch (java.io.IOException e) {
            error = "Connection failed: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        recordDelivery(eventType, projectId, projectName, referenceId,
                WebhookDeliveryStatus.FAILED, status == 0 ? null : status, error, attempt, payload);
        log.warn("[Webhook] {} delivery failed (attempt={}, status={}): {}",
                eventType, attempt, status, error);
        return new DeliveryOutcome(false, status, error);
    }

    private void recordDelivery(WebhookEventType eventType, Long projectId, String projectName,
                                String referenceId, WebhookDeliveryStatus status, Integer httpStatus,
                                String error, int attempt, String payload) {
        try {
            WebhookDelivery delivery = WebhookDelivery.builder()
                    .eventType(eventType)
                    .projectId(projectId)
                    .projectName(truncate(projectName, 160))
                    .referenceId(truncate(referenceId, 40))
                    .status(status)
                    .httpStatus(httpStatus)
                    .errorMessage(truncate(error, 500))
                    .retryCount(attempt)
                    .payloadSummary(truncate(payload, 500))
                    .build();
            webhookDeliveryRepository.save(delivery);
        } catch (Exception e) {
            log.error("[Webhook] Failed to persist delivery history: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
