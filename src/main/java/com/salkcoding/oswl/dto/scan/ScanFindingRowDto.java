package com.salkcoding.oswl.dto.scan;

import lombok.Builder;
import lombok.Value;

/** Read-model row for {@code GET /projects/{id}/security-center/findings} — never carries the secret value. */
@Value
@Builder
public class ScanFindingRowDto {
    Long id;
    String type;
    String ruleId;
    String severity;
    String filePath;
    Integer lineNumber;
    String description;
}
