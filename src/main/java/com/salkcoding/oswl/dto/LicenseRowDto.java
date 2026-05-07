package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Schema(description = "License page row — license name, risk level, and number of components using it")
@Getter
@Builder
@AllArgsConstructor
public class LicenseRowDto {

    @Schema(description = "License name", example = "Apache-2.0")
    private final String name;

    @Schema(description = "Risk level (VIOLATION→CRITICAL, WARN→HIGH, OK→LOW)",
            example = "HIGH",
            allowableValues = {"CRITICAL", "HIGH", "MEDIUM", "LOW"})
    private final String riskLevel;

    @Schema(description = "Number of components using this license", example = "4")
    private final int libraryCount;

    @Schema(description = "Names of components using this license")
    private final List<String> libraryNames;
}
