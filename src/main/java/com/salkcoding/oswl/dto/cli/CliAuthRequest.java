package com.salkcoding.oswl.dto.cli;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CliAuthRequest {
    private String username;
    private String password;
}
