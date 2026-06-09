package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Audit log query filter")
@Data
public class AuditLogFilter {
    @Schema(description = "Filter from this timestamp (ISO-8601, inclusive)", example = "2026-01-01T00:00:00")
    private LocalDateTime startDate;
    @Schema(description = "Filter until this timestamp (ISO-8601, inclusive)", example = "2026-12-31T23:59:59")
    private LocalDateTime endDate;
    @Schema(description = "Filter by actor email (exact match)", example = "alice@example.com")
    private String actorEmail;
    @Schema(description = "Filter by action code prefix (e.g. \"USER\" matches USER.CREATE, USER.DELETE)", example = "USER")
    private String action;
}
