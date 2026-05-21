package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 프로젝트 통바의 버전 드롭다운을 채우는 가벼운 스캔 요약.
 * 프로젝트의 완료된 각 ScanResult마다 한 항목이 생성된다.
 */
@Schema(description = "Completed scan summary entry — used to populate the version dropdown in the project topbar")
@Getter
@Builder
public class VersionSummaryDto {

    @Schema(description = "ScanResult primary key — used as the ?scanId= query param", example = "42")
    private final Long scanId;

    @Schema(description = "Software version reported by the CLI at scan time; falls back to scan date if not supplied", example = "1.2.5")
    private final String version;

    @Schema(description = "Scan date formatted as yyyy.MM.dd", example = "2026.05.01")
    private final String scannedAt;

    @Schema(description = "True when this entry is the scan currently shown on the page", example = "true")
    private final boolean current;
}
