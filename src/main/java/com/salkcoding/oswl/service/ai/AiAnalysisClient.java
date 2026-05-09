package com.salkcoding.oswl.service.ai;

/**
 * Provider-agnostic analysis interface.
 * OpenAI, Anthropic, and local LLM implementations can be swapped at runtime.
 */
public interface AiAnalysisClient {

    /**
     * Generate a one-line CVE risk summary.
     *
     * @param cveId      "CVE-2024-11053"
     * @param severity   "CRITICAL"
     * @param cvssScore  9.8
     * @param cveType    "RCE"
     * @param component  component name + version
     * @return one-sentence summary
     */
    String summarizeCve(String cveId, String severity, double cvssScore,
                        String cveType, String component);

    /**
     * Generate a risk trend AI insight.
     *
     * @param projectName    project name
     * @param securityDelta  change in security issues compared to the previous version
     * @param licenseDelta   change in license issues compared to the previous version
     * @param recentVersions list of recent versions (CSV)
     * @return insight sentence
     */
    String generateRiskInsight(String projectName, int securityDelta,
                               int licenseDelta, String recentVersions);

    /**
     * Generate a license risk summary.
     *
     * @param licenseName    "Creative Commons Attribution Share Alike 4.0"
     * @param licenseStatus  "RESTRICTED"
     * @param component      component name
     * @return one-sentence description
     */
    String summarizeLicenseRisk(String licenseName, String licenseStatus, String component);
}
