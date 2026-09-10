package com.salkcoding.oswl.domain.enums;

/**
 * Supported incoming-webhook providers. The payload shape differs per provider,
 * but both are simple HTTPS POST requests with a JSON body.
 */
public enum WebhookProvider {
    SLACK,
    TEAMS
}
