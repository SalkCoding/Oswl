package com.salkcoding.oswl.dto;

import com.salkcoding.oswl.domain.enums.ProjectMemberRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AddProjectMemberRequest {

    @NotBlank
    @Email
    private String email;

    private ProjectMemberRole role;
}
