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
    @Schema(description = "SHA-256 hash of the preceding audit log entry", example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    private String prevHash;
    @Schema(description = "SHA-256 integrity hash of this entry", example = "b5d4045c3f466fa91fe2cc6abe79232a1a57cdf104f7a26e716e0a1e2789df78")
    private String hash;
}
