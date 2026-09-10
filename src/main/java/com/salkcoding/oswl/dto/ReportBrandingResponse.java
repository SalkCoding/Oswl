package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Current report branding settings")
public record ReportBrandingResponse(
        String companyName,
        String logoDataUri,
        String headerText,
        boolean showCoverPage
) {}
