package com.salkcoding.oswl.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Set;

@Data
public class RoleTemplateRequest {

    @NotBlank @Size(max = 100)
    private String name;

    private String description;

    private Set<String> permissions;
}
