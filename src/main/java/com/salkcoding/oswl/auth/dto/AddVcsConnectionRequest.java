package com.salkcoding.oswl.auth.dto;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddVcsConnectionRequest {

    @NotNull
    private VcsProvider provider;

    private String serverUrl;

    @NotBlank
    private String accessToken;

    private String vcsUsername;
}
