package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class AiPromptsResponse {
    private final List<String> editableKeys;
    private final Map<String, String> resolvedTemplates;
    private final Map<String, String> overrides;
}
