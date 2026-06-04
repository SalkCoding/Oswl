package com.salkcoding.oswl.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Schema(description = "Feedback on an AI-generated summary")
@Getter
@Setter
public class AiFeedbackRequest {

    @Schema(description = "Subject type", example = "CVE")
    @NotBlank
    private String targetType;

    @Schema(description = "Stable subject key", example = "1:42:9001")
    @NotBlank
    private String targetKey;

    @Schema(description = "Whether the summary was helpful")
    @NotNull
    private Boolean helpful;

    @Schema(description = "Optional free-text comment")
    private String comment;
}
