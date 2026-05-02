package com.salkcoding.oswl.dto.scan;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLI 클라이언트가 POST /api/scan 으로 전송하는 페이로드.
 *
 * <pre>
 * {
 *   "version": "1.2.5",
 *   "components": [
 *     {
 *       "name": "lodash",
 *       "version": "4.17.21",
 *       "dependencyInfo": "Direct (1) + Transitive (3) · Projects (2)",
 *       "patchability": "patchable",
 *       "licenseStatus": "OK",
 *       "licenseName": "MIT",
 *       "cves": [
 *         {
 *           "cveId": "CVE-2021-23337",
 *           "severity": "HIGH",
 *           "cvssScore": 7.2,
 *           "type": "Injection",
 *           "discoveredOn": "2021-02-15",
 *           "affects": "Direct dep.",
 *           "fixVersion": "4.17.22"
 *         }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 */
@Getter
@NoArgsConstructor
public class ScanPayload {

    @NotBlank
    private String version;

    @Valid
    private List<ComponentPayload> components;

    /** 원시 JSON 저장용 (컨트롤러에서 주입) */
    private String rawJson;

    public void setRawJson(String rawJson) {
        this.rawJson = rawJson;
    }

    // ── 내부 DTO ──────────────────────────────────────────────────────────

    @Getter
    @NoArgsConstructor
    public static class ComponentPayload {
        @NotBlank
        private String name;
        private String version;
        private String dependencyInfo;
        private String patchability;
        private String licenseStatus;
        private String licenseName;

        @Valid
        private List<CvePayload> cves;
    }

    @Getter
    @NoArgsConstructor
    public static class CvePayload {
        @NotBlank
        private String cveId;
        private String severity;
        private Double cvssScore;
        private String type;
        private String discoveredOn;
        private String affects;
        private String fixVersion;
    }
}
