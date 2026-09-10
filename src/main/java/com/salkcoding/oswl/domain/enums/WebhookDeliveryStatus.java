package com.salkcoding.oswl.domain.enums;

/**
 * Outcome of a webhook delivery attempt. A single event may produce multiple
 * attempts if retries are configured.
 */
public enum WebhookDeliveryStatus {
    SUCCESS,
    FAILED
}
