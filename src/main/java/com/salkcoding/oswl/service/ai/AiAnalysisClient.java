package com.salkcoding.oswl.service.ai;

/**
 * Provider-agnostic analysis interface.
 * OpenAI, Anthropic, and local LLM implementations can be swapped at runtime.
 */
public interface AiAnalysisClient {

    /**
     * Generates a one-line CVE risk summary.
     *
     * @param cveId      "CVE-2024-11053"
     * @param severity   "CRITICAL"
     * @param cvssScore  9.8
     * @param cveType    "RCE"
     * @param component  Component name + version
     * @return One-sentence summary
     */
    String summarizeCve(String cveId, String severity, double cvssScore,
                        String cveType, String component);

    /**
     * Generates AI risk-trend insight.
     *
     * @param projectName    Project name
     * @param securityDelta  Change in security issues compared to the previous version
     * @param licenseDelta   Change in license issues compared to the previous version
     * @param recentVersions Recent version list (CSV)
     * @return Insight sentence
     */
    String generateRiskInsight(String projectName, int securityDelta,
                               int licenseDelta, String recentVersions);

    /**
     * Generates a license risk summary.
     *
     * @param licenseName    "Creative Commons Attribution Share Alike 4.0"
     * @param licenseStatus  "RESTRICTED"
     * @param component      Component name
     * @return One-sentence description
     */
    String summarizeLicenseRisk(String licenseName, String licenseStatus, String component);
}
