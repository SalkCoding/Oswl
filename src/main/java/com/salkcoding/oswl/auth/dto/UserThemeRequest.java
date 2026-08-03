package com.salkcoding.oswl.auth.dto;

import com.salkcoding.oswl.auth.enums.UserThemeMode;
import jakarta.validation.constraints.NotNull;
import lombok.Value;

@Value
public class UserThemeRequest {

    @NotNull
    UserThemeMode theme;
}
