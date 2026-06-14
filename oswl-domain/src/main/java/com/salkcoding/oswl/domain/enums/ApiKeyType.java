package com.salkcoding.oswl.domain.enums;

/**
 * STANDARD — transport key; scan submit still requires submitter password.
 * MACHINE — CI key bound to a user; {@code POST /api/scan} accepts Bearer token only (no password).
 */
public enum ApiKeyType {
    STANDARD,
    MACHINE
}
