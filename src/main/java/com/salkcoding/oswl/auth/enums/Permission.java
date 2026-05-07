package com.salkcoding.oswl.auth.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Permission {
    PROJECT_VIEW("프로젝트 조회"),
    PROJECT_CREATE("프로젝트 생성"),
    PROJECT_DELETE("프로젝트 소프트 삭제"),
    PROJECT_RESTORE("프로젝트 복구"),
    PROJECT_PERMANENT_DELETE("프로젝트 영구 삭제"),

    SCAN_SUBMIT("스캔 제출"),
    SCAN_VIEW("스캔 결과 조회"),
    SCAN_HISTORY_VIEW("스캔 히스토리 조회"),

    SECURITY_CENTER_VIEW("보안 센터 조회"),
    SECURITY_CENTER_UPDATE_STATUS("취약점 상태 변경"),
    SECURITY_CENTER_EXPORT("결과 내보내기"),

    LICENSE_VIEW("라이선스 분석 조회"),
    LICENSE_POLICY_MANAGE("라이선스 정책 편집"),

    COMPONENT_DETAIL_VIEW("컴포넌트 상세 조회"),
    VERSION_DIFF_VIEW("버전 비교 조회"),
    RISK_TREND_VIEW("위험 추이 조회"),

    SETTINGS_AI_MANAGE("AI 설정 관리"),
    SETTINGS_VCS_MANAGE("VCS 계정 연결 관리"),
    SETTINGS_CLI_KEY_MANAGE("CLI API 키 관리"),
    SETTINGS_CACHE_MANAGE("캐시 설정 관리");

    private final String description;
}
