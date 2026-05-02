package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

/** API 키 발급 직후에만 반환 — 이후 조회에서는 마스킹된 ApiKeyResponse 사용 */
@Getter
@Builder
public class ApiKeyIssueResponse {
    private final Long   id;
    private final String token;    // 전체 토큰 (최초 발급 시 1회만 노출)
    private final String label;
    private final String createdAt;
    private final String message;
}
