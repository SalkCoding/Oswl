package com.salkcoding.oswl.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class UserSummaryDto {
    private Long id;
    private String email;
    private String displayName;
    private boolean systemAdmin;
    private boolean enabled;
    private LocalDateTime createdAt;
    private List<RoleTemplateRefDto> roleTemplates;
}
