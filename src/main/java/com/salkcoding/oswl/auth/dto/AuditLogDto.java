package com.salkcoding.oswl.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
public class AuditLogDto {
    private Long id;
    private String actorEmail;
    private String action;
    private String targetType;
    private String targetName;
    private LocalDateTime createdAt;
    private String detail;
}
