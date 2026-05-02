package com.salkcoding.oswl.dto.api;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PingResponse {
    private final String status;
    private final Long   projectId;
}
