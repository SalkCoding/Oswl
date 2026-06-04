package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Schema(description = "Editable AI prompt templates and overrides")
@Getter
@Builder
public class AiPromptsResponse {

    @Schema(description = "Prompt keys that may be overridden in settings")
    private final List<String> editableKeys;

    @Schema(description = "Effective template text per key (locale + DB overrides applied)")
    private final Map<String, String> resolvedTemplates;

    @Schema(description = "Raw override map from preferences JSON")
    private final Map<String, String> overrides;
}
