package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GlobalApiKeyResponse {
    private final Long    id;
    private final String  token;
    private final Long    projectId;
    private final String  projectName;
    private final String  label;
    private final boolean active;
    private final String  createdAt;
    private final String  lastUsedAt;
}
