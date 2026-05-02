package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ApiKeyResponse {
    private final Long    id;
    private final String  token;       // 마스킹된 값
    private final String  label;
    private final boolean active;
    private final String  lastUsedAt;  // nullable → null이면 JSON에서 제외 (non_null 설정)
    private final String  createdAt;
}
