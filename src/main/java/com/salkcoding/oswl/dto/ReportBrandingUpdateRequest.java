package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Request body for updating report branding settings")
public record ReportBrandingUpdateRequest(
        @Schema(example = "Acme Corp")
        @Size(max = 150, message = "companyName must be at most 150 characters")
        String companyName,

        @Schema(description = "Image data URI (data:image/...;base64,...). Null = leave unchanged, blank = remove.")
        String logoDataUri,

        @Schema(example = "Confidential — internal distribution only")
        @Size(max = 300, message = "headerText must be at most 300 characters")
        String headerText,

        boolean showCoverPage
) {}
