package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

@Schema(description = "API key validation response")
@Getter
@Builder
public class PingResponse {

    @Schema(description = "Request processing status", example = "ok")
    private final String status;

    @Schema(description = "Project ID that the API key belongs to", example = "1")
    private final Long projectId;
}
