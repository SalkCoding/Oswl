package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Audit log entry")
@Data
@Builder
@AllArgsConstructor
public class AuditLogDto {
    @Schema(description = "Log entry primary key", example = "1001")
    private Long id;
    @Schema(description = "Display name of the user who performed the action", example = "Alice")
    private String actorDisplayName;
    @Schema(description = "Email of the user who performed the action", example = "alice@example.com")
    private String actorEmail;
    @Schema(description = "IP address of the request", example = "192.168.1.10")
    private String actorIp;
    @Schema(description = "Action code (e.g. USER.CREATE, SCAN.DELETE)", example = "USER.CREATE")
    private String action;
    @Schema(description = "Target entity type (e.g. USER, SCAN_RESULT)", example = "USER")
    private String targetType;
    @Schema(description = "Human-readable target name", example = "alice@example.com")
    private String targetName;
    @Schema(description = "Event timestamp (ISO-8601)", example = "2026-05-20T08:30:00")
    private LocalDateTime createdAt;
    @Schema(description = "Optional extra detail", example = "roles=[Developer]")
    private String detail;
}
