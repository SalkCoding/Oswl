package com.salkcoding.oswl.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScanHistoryRowDto {

    private final Long   scanId;
    private final String version;
    private final String status;       // COMPLETED, FAILED, SCANNING, ANALYZING, PENDING
    private final String scannedAt;    // formatted "YYYY-MM-DD HH:mm"
    private final long   componentCount;
    private final String errorMessage; // null unless FAILED
}
