package com.salkcoding.oswl.auth.dto;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "VCS connection summary")
@Data
@Builder
@AllArgsConstructor
public class VcsConnectionDto {
    @Schema(description = "VCS connection primary key", example = "1")
    private Long id;
    @Schema(description = "VCS provider type", example = "GITHUB",
            allowableValues = {"GITHUB", "GITLAB", "BITBUCKET"})
    private VcsProvider provider;
    @Schema(description = "Self-hosted server URL (null for cloud providers)", example = "https://gitlab.mycompany.com")
    private String serverUrl;
    @Schema(description = "VCS account username", example = "octocat")
    private String vcsUsername;
    @Schema(description = "Connection creation timestamp (ISO-8601)", example = "2026-03-10T11:00:00")
    private LocalDateTime createdAt;
}
