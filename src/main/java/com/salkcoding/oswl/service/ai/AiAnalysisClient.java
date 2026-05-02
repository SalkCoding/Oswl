package com.salkcoding.oswl.service.ai;

/**
 * AI 제공자에 무관한 분석 인터페이스.
 * OpenAI, Anthropic, 로컬 LLM 구현체를 런타임에 교체할 수 있다.
 */
public interface AiAnalysisClient {

    /**
     * CVE 한줄 위험 요약 생성.
     *
     * @param cveId      "CVE-2024-11053"
     * @param severity   "CRITICAL"
     * @param cvssScore  9.8
     * @param cveType    "RCE"
     * @param component  컴포넌트명 + 버전
     * @return 한 문장 요약
     */
    String summarizeCve(String cveId, String severity, double cvssScore,
                        String cveType, String component);

    /**
     * 리스크 트렌드 AI Insight 생성.
     *
     * @param projectName 프로젝트명
     * @param securityDelta 이전 버전 대비 보안 이슈 증감
     * @param licenseDelta  이전 버전 대비 라이선스 이슈 증감
     * @param recentVersions 최근 버전 목록 (CSV)
     * @return 인사이트 문장
     */
    String generateRiskInsight(String projectName, int securityDelta,
                               int licenseDelta, String recentVersions);

    /**
     * 라이선스 위험 요약 생성.
     *
     * @param licenseName    "Creative Commons Attribution Share Alike 4.0"
     * @param licenseStatus  "VIOLATION"
     * @param component      컴포넌트명
     * @return 한 문장 설명
     */
    String summarizeLicenseRisk(String licenseName, String licenseStatus, String component);
}
