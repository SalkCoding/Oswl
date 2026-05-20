package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiTestConnectionRequest {

    @NotNull
    private AiProvider provider;

    /** Plain-text API key from the form. Null/blank = use stored encrypted key. */
    private String apiKey;

    private String modelName;

    private String baseUrl;
}
