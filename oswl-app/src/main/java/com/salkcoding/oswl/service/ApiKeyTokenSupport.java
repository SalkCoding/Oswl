package com.salkcoding.oswl.service;

/**
 * Helpers for CLI API key prefix extraction and display masking.
 */
public final class ApiKeyTokenSupport {

    /** Indexed lookup prefix — first 16 characters of the full token. */
    public static final int PREFIX_LENGTH = 16;

    private ApiKeyTokenSupport() {}

    public static String extractPrefix(String rawToken) {
        if (rawToken == null || rawToken.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("API key is too short");
        }
        return rawToken.substring(0, PREFIX_LENGTH);
    }

    public static String maskForDisplay(String tokenPrefix) {
        if (tokenPrefix == null || tokenPrefix.length() < 10) {
            return "***";
        }
        return tokenPrefix + "...";
    }
}
