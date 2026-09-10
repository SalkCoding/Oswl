package com.salkcoding.oswl.dto;

/**
 * Minimal id+name reference to an active project, for SYSTEM_ADMIN-only pickers
 * (e.g. the scan-archiving panel) that need a project dropdown without the full
 * {@link ProjectSummaryDto} card payload.
 */
public record AdminProjectRefDto(
        Long id,
        String name
) {}
