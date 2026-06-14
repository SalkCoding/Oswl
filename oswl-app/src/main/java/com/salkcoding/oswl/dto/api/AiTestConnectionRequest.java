package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "AI provider connection test request")
@Getter
@Setter
public class AiTestConnectionRequest {

    @Schema(description = "AI provider to test", example = "OPENAI",
            allowableValues = {"OPENAI", "ANTHROPIC", "LOCAL", "GEMINI"})
    @NotNull
    private AiProvider provider;

    @Schema(description = "API key for the test (null/blank = use stored encrypted key)", example = "sk-abc...xyz")
    /** Plain-text API key from the form. Null/blank = use stored encrypted key. */
    private String apiKey;

    @Schema(description = "Model name to test", example = "gpt-4o-mini")
    private String modelName;

    @Schema(description = "Custom endpoint URL (LOCAL provider only)", example = "http://localhost:11434/v1")
    private String baseUrl;
}
