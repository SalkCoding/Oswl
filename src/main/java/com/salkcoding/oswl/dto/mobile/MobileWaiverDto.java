package com.salkcoding.oswl.dto.mobile;

/**
 * One pending policy exception (waiver) row for the mobile approval view (ROADMAP C8).
 * {@code expiry} is pre-formatted server-side (not a raw LocalDateTime) — see
 * {@link MobileAlertDto} for why.
 */
public record MobileWaiverDto(
        Long id,
        Long projectId,
        String projectName,
        String requesterName,
        String reason,
        String expiry,
        String targetType,
        String targetId
) {}
