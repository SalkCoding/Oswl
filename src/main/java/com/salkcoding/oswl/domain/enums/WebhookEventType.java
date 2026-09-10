package com.salkcoding.oswl.domain.enums;

/**
 * Webhook notification event types. Each event can be toggled independently
 * in the webhook settings.
 */
public enum WebhookEventType {
    NEW_CVE,
    GATE_FAILURE,
    SCAN_FAILURE,
    WAIVER_EXPIRY
}
