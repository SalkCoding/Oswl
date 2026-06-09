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

    @Schema(description = "Prompt response language", example = "en", allowableValues = {"en", "ko"})
    private String     promptsLocale;

    @Schema(description = "Max Critical/High CVEs summarized per scan enrichment", example = "10")
    private Integer    cveLimit;

    @Schema(description = "Max license-risk components summarized per scan enrichment", example = "8")
    private Integer    licenseLimit;

    @Schema(description = "Comma-separated CVE severities for AI batch (e.g. CRITICAL,HIGH,MEDIUM)",
            example = "CRITICAL,HIGH")
    private String     cveSeverities;

    @Schema(description = "LLM temperature (0.0–2.0); omit to use template default", example = "0.15")
    private Double     temperature;

    @Schema(description = "Max output tokens per call; omit to use template default", example = "1200")
    private Integer    maxTokens;

    @Schema(description = "Max LLM API calls per provider per day (0 = unlimited)", example = "500")
    private Integer    dailyCallCap;

    @Schema(description = "JSON map of prompt key → override template text")
    private String     promptOverrides;

    @Schema(description = "Default deployment profile for projects without one",
            example = "COMMERCIAL_PRODUCT")
    private String     defaultDeploymentProfile;
}
