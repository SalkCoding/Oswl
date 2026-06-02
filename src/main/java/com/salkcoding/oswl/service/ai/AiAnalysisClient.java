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
     * Generates a license risk summary.
     *
     * @param licenseName    "Creative Commons Attribution Share Alike 4.0"
     * @param licenseStatus  "RESTRICTED"
     * @param component      Component name
     * @return One-sentence description
     */
    String summarizeLicenseRisk(String licenseName, String licenseStatus, String component);
}
