package com.salkcoding.oswl.auth.dto;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "Add a new VCS connection request")
@Data
public class AddVcsConnectionRequest {

    @Schema(description = "VCS provider type", example = "GITHUB",
            allowableValues = {"GITHUB", "GITLAB", "BITBUCKET"})
    @NotNull
    private VcsProvider provider;

    @Schema(description = "Self-hosted server URL (required for GITLAB/BITBUCKET self-hosted; omit for cloud)",
            example = "https://gitlab.mycompany.com")
    private String serverUrl;

    @Schema(description = "Personal access token or app password for authentication", example = "ghp_abc123xyz")
    @NotBlank
    private String accessToken;

    @Schema(description = "VCS account username (required for Bitbucket; optional for others)", example = "octocat")
    private String vcsUsername;
}
