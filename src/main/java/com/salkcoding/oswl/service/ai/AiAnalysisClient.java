package com.salkcoding.oswl.service.ai;

/**
 * 프로바이더 독립적 분석 인터페이스.
 * OpenAI, Anthropic, 로컈 LLM 구현체를 런타임에 교체할 수 있다.
 */
public interface AiAnalysisClient {

    /**
     * CVE 리스크 한 줄 요약을 생성한다.
     *
     * @param cveId      "CVE-2024-11053"
     * @param severity   "CRITICAL"
     * @param cvssScore  9.8
     * @param cveType    "RCE"
     * @param component  컴포넌트 이름 + 버전
     * @return 한 문장 요약
     */
    String summarizeCve(String cveId, String severity, double cvssScore,
                        String cveType, String component);

    /**
     * 리스크 트렌드 AI 인사이트를 생성한다.
     *
     * @param projectName    프로젝트 이름
     * @param securityDelta  이전 버전 대비 보안 이슈 변화량
     * @param licenseDelta   이전 버전 대비 라이선스 이슈 변화량
     * @param recentVersions 최근 버전 목록 (CSV)
     * @return 인사이트 문장
     */
    String generateRiskInsight(String projectName, int securityDelta,
                               int licenseDelta, String recentVersions);

    /**
     * 라이선스 리스크 요약을 생성한다.
     *
     * @param licenseName    "Creative Commons Attribution Share Alike 4.0"
     * @param licenseStatus  "RESTRICTED"
     * @param component      컴포넌트 이름
     * @return 한 문장 설명
     */
    String summarizeLicenseRisk(String licenseName, String licenseStatus, String component);
}
