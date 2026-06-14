package com.salkcoding.oswl.dto;

import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ProjectMemberDto {
    Long userId;
    String email;
    String displayName;
    ProjectMemberRole role;
}
