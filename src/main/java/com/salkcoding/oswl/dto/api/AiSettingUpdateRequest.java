package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "AI provider configuration and activation request")
@Getter
@Setter
public class AiSettingUpdateRequest {

    @Schema(description = "AI provider (required)", example = "OPENAI",
            allowableValues = {"OPENAI", "ANTHROPIC", "LOCAL", "GEMINI"})
    @NotNull
    private AiProvider provider;

    @Schema(description = "API key (not required for LOCAL provider)", example = "sk-abc...xyz")
    private String     apiKey;

    @Schema(description = "Model name to use", example = "gpt-4o-mini")
    private String     modelName;

    @Schema(description = "Custom LLM endpoint (LOCAL provider only)",
            example = "http://localhost:11434/v1")
    private String     baseUrl;

    @Schema(description = "If true, activates the provider upon save", example = "true")
    private Boolean    activate;
}
