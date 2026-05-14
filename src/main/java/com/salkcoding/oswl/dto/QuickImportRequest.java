package com.salkcoding.oswl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class QuickImportRequest {

    /** Full repository URL, e.g. https://github.com/owner/repo */
    @NotBlank
    private String repoUrl;

    /** Branch to clone. Null/blank → use the repository's default branch. */
    private String branch;
}
