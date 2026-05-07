package com.salkcoding.oswl.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateUserRequest {

    @NotBlank @Size(max = 100)
    private String displayName;

    @NotBlank @Email
    private String email;

    @NotBlank @Size(min = 8)
    private String temporaryPassword;

    private List<Long> templateIds;
}
