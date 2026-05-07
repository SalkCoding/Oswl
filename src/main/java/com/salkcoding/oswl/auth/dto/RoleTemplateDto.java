package com.salkcoding.oswl.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
@AllArgsConstructor
public class RoleTemplateDto {
    private Long id;
    private String name;
    private String description;
    private boolean builtIn;
    private Set<String> permissions;
    private long userCount;
}
