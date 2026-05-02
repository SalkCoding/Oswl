package com.salkcoding.oswl.dto.api;

import com.salkcoding.oswl.domain.enums.AiProvider;
import lombok.Builder;
import lombok.Getter;

/**
 * AI 설정 응답 DTO.
 * 미설정 상태일 때는 provider~active가 null이고 message만 채워진다.
 * (application.yaml: spring.jackson.default-property-inclusion: non_null 설정으로 null 필드 제외)
 */
@Getter
@Builder
public class AiSettingResponse {
    private final AiProvider provider;
    private final String     modelName;
    private final String     baseUrl;
    private final String     apiKey;    // 마스킹된 값
    private final Boolean    active;
    private final String     message;  // 미설정 시 안내 메시지
}
