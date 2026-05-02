package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScanResponse {
    private final Long   scanId;
    private final Long   projectId;
    private final String version;
    private final String status;
    private final String message;
}
