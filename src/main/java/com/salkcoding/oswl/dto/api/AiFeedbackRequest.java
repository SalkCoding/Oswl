package com.salkcoding.oswl.dto.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiFeedbackRequest {
    @NotBlank
    private String targetType;
    @NotBlank
    private String targetKey;
    @NotNull
    private Boolean helpful;
    private String comment;
}
