package com.salkcoding.oswl.dto.mobile;

/**
 * One unacknowledged CVE alert row for the mobile notifications view (ROADMAP C8).
 * {@code detectedAt} is pre-formatted server-side (not a raw LocalDateTime) — this DTO is
 * serialized straight into a {@code th:inline="javascript"} block, and Thymeleaf's inliner
 * serializing java.time types is not something to rely on for a display string.
 */
public record MobileAlertDto(
        Long id,
        Long projectId,
        String projectName,
        String libraryName,
        String libraryVersion,
        String vulnId,
        String severity,
        String detectedAt
) {}
