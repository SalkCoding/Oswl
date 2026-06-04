package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "AI analysis settings response — when not configured, provider through active are null, only message is returned")
@Getter
@Builder
public class AiSettingResponse {

    @Schema(description = "AI provider", example = "OPENAI",
            allowableValues = {"OPENAI", "ANTHROPIC", "LOCAL", "GEMINI"})
    private final AiProvider provider;

    @Schema(description = "Model name in use", example = "gpt-4o-mini")
    private final String     modelName;

    @Schema(description = "Custom endpoint URL for local LLM (only applicable for LOCAL provider)",
            example = "http://localhost:11434/v1")
    private final String     baseUrl;

    @Schema(description = "API key (masked — only first 4 characters exposed)", example = "sk-...abc")
    private final String     apiKey;

    @Schema(description = "Whether this provider is active", example = "true")
    private final Boolean    active;

    @Schema(description = "Guidance message (returned only when not configured)",
            example = "AI setting is not configured. Use PUT /api/settings/ai to configure.")
    private final String     message;

    @Schema(description = "Prompt response language", example = "en", allowableValues = {"en", "ko"})
    private final String     promptsLocale;

    @Schema(description = "Max Critical/High CVEs summarized per scan enrichment", example = "10")
    private final Integer    cveLimit;

    @Schema(description = "Max license-risk components summarized per scan enrichment", example = "8")
    private final Integer    licenseLimit;

    @Schema(description = "CVE severities included in AI batch summaries", example = "CRITICAL,HIGH")
    private final String     cveSeverities;

    private final Double     temperature;
    private final Integer    maxTokens;
    private final Integer    dailyCallCap;
    private final String     promptOverrides;
    private final String     defaultDeploymentProfile;
}
