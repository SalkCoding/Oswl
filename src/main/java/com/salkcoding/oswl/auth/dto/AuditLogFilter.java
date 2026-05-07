package com.salkcoding.oswl.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogFilter {
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private String actorEmail;
    private String action;
}
