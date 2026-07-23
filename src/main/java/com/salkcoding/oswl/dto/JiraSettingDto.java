package com.salkcoding.oswl.dto;

/**
 * Jira settings view/save DTO. {@code hasToken} indicates a token is stored (the token
 * itself is never returned); on save, a blank token keeps the existing one.
 */
public record JiraSettingDto(
        String baseUrl,
        String email,
        boolean hasToken,
        String projectKey,
        String issueType,
        boolean enabled) {}
