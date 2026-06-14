package com.salkcoding.oswl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "Start a Quick Import job from a repository URL")
@Getter
@NoArgsConstructor
public class QuickImportRequest {

    @Schema(description = "Full repository URL", example = "https://github.com/owner/repo")
    /** Full repository URL, e.g. https://github.com/owner/repo */
    @NotBlank
    private String repoUrl;

    @Schema(description = "Branch to clone (null/blank = repository default branch)", example = "main")
    /** Branch to clone. Null/blank → use the repository's default branch. */
    private String branch;
}
