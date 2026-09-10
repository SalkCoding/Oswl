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
    private final String importSource; // GIT, CLI, or null

    /** True once the scan's component/CVE detail has been deleted by the retention policy. */
    private final boolean archived;
    // Populated only when archived — the aggregate the retention policy stamped before deleting detail.
    private final Integer archivedSecurityCritical;
    private final Integer archivedSecurityHigh;
    private final Integer archivedLicenseCritical;
    private final Integer archivedLicenseHigh;
}
