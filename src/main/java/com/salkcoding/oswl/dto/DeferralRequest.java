package com.salkcoding.oswl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * POST /projects/{projectId}/components/{componentId}/defer 페이로드
 */
@Getter
@NoArgsConstructor
public class DeferralRequest {

    /** 사유 코드: legal-review | false-positive | wont-fix | temporary | other */
    private String reason;

    /** reason = "other"일 때의 자유 텍스트 */
    private String otherText;

    /**
     * 만료 프리셋: 1-week | 1-month | 3-month | 6-month | custom | indefinite
     */
    private String expiry;

    /** expiry = "custom"일 때 사용하는 ISO 날짜 문자열 (YYYY-MM-DD) */
    private String customDate;

    /**
     * 유예 범위: "project"(해당 scan component만) 또는
     * "all-projects"(동일 Library를 참조하는 모든 ScanComponent 행).
     */
    private String scope;

    /** Optional note / PR description text */
    private String prDescription;
}
