package com.salkcoding.oswl.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SetupRequest {

    @NotBlank @Size(min = 1, max = 100)
    private String displayName;

    @NotBlank @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", message = "올바른 이메일 형식이 아닙니다.")
    private String email;

    @NotBlank @Size(min = 8, max = 100)
    private String password;

    @NotBlank
    private String passwordConfirm;
}
