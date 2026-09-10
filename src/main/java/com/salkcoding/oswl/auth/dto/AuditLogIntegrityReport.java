package com.salkcoding.oswl.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "Audit log integrity chain verification report")
@Data
@Builder
@AllArgsConstructor
public class AuditLogIntegrityReport {

    @Schema(description = "True when no broken chain links were detected among hashed entries")
    private boolean verified;

    @Schema(description = "Total audit log rows examined")
    private long total;

    @Schema(description = "Rows that have no stored hash (legacy entries created before the chain was enabled)")
    private long unhashedCount;

    @Schema(description = "Number of rows that failed integrity or continuity checks")
    private long brokenCount;

    @Schema(description = "Primary key of the first failing entry, if any")
    private Long firstBrokenId;

    @Schema(description = "Primary key of the last entry processed")
    private Long lastId;

    @Schema(description = "Up to 100 identifiers of failing entries")
    @Builder.Default
    private List<Long> brokenIds = new ArrayList<>();
}
